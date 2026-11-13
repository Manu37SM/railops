package in.railops.payment.service;

import in.railops.common.messaging.OutboxWriter;
import in.railops.common.messaging.ProcessedEvents;
import in.railops.common.messaging.Topics;
import in.railops.payment.gateway.GatewayPort;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Refunds: accept (from RefundRequested events), submit to the gateway with backoff, settle on webhook.
 *
 * <p>Money invariant: the sum of committed refunds (everything except MANUAL_REVIEW) never exceeds the captured amount. Checked
 * under the payment row lock, so two concurrent cancellations can't both pass the check. A request that would
 * breach it goes to MANUAL_REVIEW with an alert, never to the gateway.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);
    static final String CONSUMER = "payment.refund-requests";
    private static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(12));

    public record RefundRequested(String pnr, UUID paymentId, BigDecimal amount, String reason, String idempotencyKey) {
    }

    private record Claimed(UUID id, UUID paymentId, String orderId, BigDecimal amount, int attempts, String pnr) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final GatewayPort gateway;
    private final ProcessedEvents processed;
    private final OutboxWriter outbox;
    private final MeterRegistry meters;

    public RefundService(JdbcClient jdbc, TransactionTemplate tx, GatewayPort gateway, ProcessedEvents processed,
                         OutboxWriter outbox, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.gateway = gateway;
        this.processed = processed;
        this.outbox = outbox;
        this.meters = meters;
    }

    public void accept(UUID eventId, RefundRequested r) {
        tx.executeWithoutResult(s -> {
            if (!processed.markProcessed(CONSUMER, eventId)) {
                return;
            }
            var payment = jdbc.sql("SELECT status, captured_amount FROM payment.payment WHERE id = :id FOR UPDATE")
                    .param("id", r.paymentId())
                    .query((rs, n) -> new Object[]{rs.getString(1), rs.getBigDecimal(2)}).optional().orElse(null);
            // Committed = will be (or was) paid. FAILED counts: it's retried automatically. MANUAL_REVIEW does NOT
            // count: it's blocked until a human approves it, and approval must re-run this check. (The first version
            // had both backwards: a blocked bogus refund froze legitimate ones, and a retrying FAILED one was ignored,
            // which is an actual over-refund path.)
            BigDecimal alreadyRefunding = jdbc.sql("""
                            SELECT COALESCE(sum(amount), 0) FROM payment.refund
                            WHERE payment_id = :p AND status IN ('REQUESTED','SUBMITTING','SUBMITTED','SUCCEEDED','FAILED')
                            """).param("p", r.paymentId()).query(BigDecimal.class).single();
            String status = "REQUESTED";
            String error = null;
            if (payment == null || !"CAPTURED".equals(payment[0])) {
                status = "MANUAL_REVIEW";
                error = "payment not captured";
            } else if (alreadyRefunding.add(r.amount()).compareTo((BigDecimal) payment[1]) > 0) {
                status = "MANUAL_REVIEW";
                error = "would refund " + alreadyRefunding.add(r.amount()) + " of " + payment[1] + " captured";
                meters.counter("payment.refund.over_refund_blocked").increment();
                log.error("BLOCKED over-refund for {}: {}", r.pnr(), error);
            }
            if (payment == null) {
                log.error("refund requested for unknown payment {} (PNR {})", r.paymentId(), r.pnr());
                return; // can't insert: FK to payment. Alerted via log + metric; the DLT isn't the right place.
            }
            int inserted = jdbc.sql("""
                            INSERT INTO payment.refund (id, payment_id, pnr, reason, amount, status, idempotency_key, last_error)
                            VALUES (:id, :p, :pnr, :reason, :a, :s, :k, :err) ON CONFLICT (idempotency_key) DO NOTHING
                            """)
                    .param("id", UUID.randomUUID()).param("p", r.paymentId()).param("pnr", r.pnr())
                    .param("reason", r.reason()).param("a", r.amount()).param("s", status)
                    .param("k", r.idempotencyKey()).param("err", error)
                    .update();
            if (inserted == 0) {
                log.info("refund {} already recorded; duplicate request ignored", r.idempotencyKey());
            }
        });
    }

    /** One pass of the refund worker. */
    public int submitDue(int limit) {
        List<Claimed> claimed = tx.execute(s -> jdbc.sql("""
                        UPDATE payment.refund r SET status = 'SUBMITTING', attempts = attempts + 1, updated_at = now(),
                               next_attempt_at = now() + interval '5 minutes'  -- reclaimed if we crash mid-call
                        FROM payment.payment p
                        WHERE p.id = r.payment_id AND r.id IN (
                              SELECT id FROM payment.refund
                              WHERE status IN ('REQUESTED','FAILED','SUBMITTING') AND next_attempt_at <= now()
                              ORDER BY next_attempt_at LIMIT :n FOR UPDATE SKIP LOCKED)
                        RETURNING r.id, r.payment_id, p.gateway_order_id, r.amount, r.attempts, p.pnr
                        """).param("n", limit)
                .query((rs, n) -> new Claimed(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getBigDecimal(4), rs.getInt(5), rs.getString(6)))
                .list());
        for (Claimed c : claimed) {
            try {
                GatewayPort.RefundAck ack = gateway.refund(c.orderId(), c.id(), c.amount());
                tx.executeWithoutResult(s -> jdbc.sql("""
                                UPDATE payment.refund SET status = 'SUBMITTED', gateway_refund_id = :g, updated_at = now(),
                                       next_attempt_at = now() + interval '1 day', last_error = NULL
                                WHERE id = :id AND status = 'SUBMITTING'
                                """).param("g", ack.gatewayRefundId()).param("id", c.id()).update());
            } catch (RuntimeException e) {
                boolean giveUp = c.attempts() >= BACKOFF.size();
                tx.executeWithoutResult(s -> {
                    jdbc.sql("""
                                    UPDATE payment.refund SET status = :s, last_error = :e, updated_at = now(),
                                           next_attempt_at = now() + CAST(:d AS interval)
                                    WHERE id = :id
                                    """)
                            .param("s", giveUp ? "MANUAL_REVIEW" : "FAILED").param("e", e.toString())
                            .param("d", BACKOFF.get(Math.min(c.attempts(), BACKOFF.size()) - 1).toSeconds() + " seconds")
                            .param("id", c.id()).update();
                    if (giveUp) {
                        // Keyed by PNR like every other payment.events.v1 event: per-PNR ordering for any consumer
                        // (was paymentId, which put it on a different partition from the PNR's other events).
                        outbox.append(Topics.PAYMENT, "Payment", c.pnr(), "RefundNeedsReview",
                                new in.railops.payment.contracts.PaymentEvents.RefundNeedsReview(c.id(), c.paymentId(), c.amount()),
                                Map.of());
                    }
                });
                log.warn("refund {} attempt {} failed: {}", c.id(), c.attempts(), e.toString());
            }
        }
        return claimed.size();
    }
}
