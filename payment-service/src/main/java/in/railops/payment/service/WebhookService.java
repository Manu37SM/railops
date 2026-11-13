package in.railops.payment.service;

import in.railops.payment.contracts.PaymentEvents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.railops.common.messaging.OutboxWriter;
import in.railops.common.messaging.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Applies verified gateway events. One transaction per event: dedupe row + state transition + ledger + outbox
 * commit together, so a crash can neither double-post the ledger nor lose the PaymentCaptured event.
 *
 * <p>A capture is recorded even when it's "wrong" (amount differs, payment already expired): the money has
 * left the passenger's account, so hiding it would be the worst outcome. Reservation receives the real
 * captured amount and decides confirm vs refund; the mismatch is also counted and logged here.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private record Payment(UUID id, String pnr, BigDecimal amount, String status, BigDecimal captured) {
    }

    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final OutboxWriter outbox;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;

    public WebhookService(JdbcClient jdbc, Ledger ledger, OutboxWriter outbox, ObjectMapper mapper, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.outbox = outbox;
        this.mapper = mapper;
        this.meters = meters;
    }

    /** @return false if the event was a duplicate delivery. */
    @Transactional
    public boolean apply(String gateway, byte[] rawBody) throws IOException {
        return apply(gateway, rawBody, rawBody);
    }

    /**
     * {@code normalized} is the internal event format ({id, type, data:{orderId, paymentId, amount, currency,
     * refundId}}) that a gateway adapter translated its own webhook into (Phase 6: Razorpay); {@code original} is
     * what the gateway actually sent, kept in webhook_event for audit.
     */
    @Transactional
    public boolean apply(String gateway, byte[] normalized, byte[] original) throws IOException {
        JsonNode event = mapper.readTree(normalized);
        byte[] rawBody = original;
        String eventId = event.path("id").asText();
        String type = event.path("type").asText();
        JsonNode data = event.path("data");
        if (eventId.isBlank() || type.isBlank()) {
            throw new IllegalArgumentException("malformed webhook");
        }
        int fresh = jdbc.sql("""
                        INSERT INTO payment.webhook_event (gateway, gateway_event_id, event_type, payload)
                        VALUES (:g, :id, :t, CAST(:p AS jsonb)) ON CONFLICT DO NOTHING
                        """)
                .param("g", gateway).param("id", eventId).param("t", type).param("p", new String(rawBody))
                .update();
        if (fresh == 0) {
            meters.counter("payment.webhook.duplicate").increment();
            return false;
        }
        switch (type) {
            case "payment.captured" -> captured(data.path("orderId").asText(), data.path("paymentId").asText(),
                    new BigDecimal(data.path("amount").asText("0")), data.path("currency").asText());
            case "payment.failed" -> failed(data.path("orderId").asText());
            case "refund.processed" -> refundSettled(data.path("refundId").asText());
            default -> log.info("ignoring gateway event type {}", type);   // incl. "ignored.*" from adapters
        }
        return true;
    }

    void captured(String orderId, String gatewayPaymentId, BigDecimal amount, String currency) {
        Payment p = lockByOrder(orderId);
        if ("CAPTURED".equals(p.status())) {
            log.warn("second capture event for payment {} (order {}); ignored", p.id(), orderId);
            return;
        }
        if (amount.compareTo(p.amount()) != 0 || !"INR".equals(currency)) {
            meters.counter("payment.capture.amount_mismatch").increment();
            log.error("payment {} captured {} {} but order amount is {}", p.id(), amount, currency, p.amount());
        }
        jdbc.sql("""
                        UPDATE payment.payment SET status = 'CAPTURED', captured_amount = :a, gateway_payment_id = :gp,
                               updated_at = now(), version = version + 1
                        WHERE id = :id
                        """).param("a", amount).param("gp", gatewayPaymentId).param("id", p.id()).update();
        ledger.capture(p.id(), p.pnr(), amount);
        outbox.append(Topics.PAYMENT, "Payment", p.pnr(), "PaymentCaptured",
                new PaymentEvents.PaymentCaptured(p.id(), p.pnr(), amount, currency), Map.of());
        meters.counter("payment.captured").increment();
    }

    void failed(String orderId) {
        Payment p = lockByOrder(orderId);
        if (!"PENDING".equals(p.status()) && !"CREATED".equals(p.status())) {
            return;
        }
        jdbc.sql("UPDATE payment.payment SET status = 'FAILED', updated_at = now(), version = version + 1 WHERE id = :id")
                .param("id", p.id()).update();
        outbox.append(Topics.PAYMENT, "Payment", p.pnr(), "PaymentFailed",
                new PaymentEvents.PaymentFailed(p.id(), p.pnr(), "DECLINED"), Map.of());
    }

    void refundSettled(String gatewayRefundId) {
        var r = jdbc.sql("""
                        SELECT r.id, r.payment_id, r.pnr, r.amount, r.status FROM payment.refund r
                        WHERE r.gateway_refund_id = :g FOR UPDATE
                        """).param("g", gatewayRefundId)
                .query((rs, n) -> new Object[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getBigDecimal(4), rs.getString(5)})
                .optional().orElseThrow(() -> new IllegalStateException("unknown gateway refund " + gatewayRefundId));
        if ("SUCCEEDED".equals(r[4])) {
            return;
        }
        jdbc.sql("UPDATE payment.refund SET status = 'SUCCEEDED', updated_at = now() WHERE id = :id").param("id", r[0]).update();
        ledger.refund((UUID) r[1], (UUID) r[0], (String) r[2], (BigDecimal) r[3]);
        outbox.append(Topics.PAYMENT, "Payment", (String) r[2], "RefundSucceeded",
                new PaymentEvents.RefundSucceeded((UUID) r[0], (UUID) r[1], (String) r[2], (BigDecimal) r[3]), Map.of());
        meters.counter("payment.refund.succeeded").increment();
    }

    private Payment lockByOrder(String orderId) {
        return jdbc.sql("""
                        SELECT id, pnr, amount, status, captured_amount FROM payment.payment
                        WHERE gateway_order_id = :o FOR UPDATE
                        """).param("o", orderId)
                .query((rs, n) -> new Payment(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3),
                        rs.getString(4), rs.getBigDecimal(5)))
                .optional()
                // Unknown order: throw so the gateway retries. It may be a webhook racing our own T2 commit.
                .orElseThrow(() -> new IllegalStateException("unknown gateway order " + orderId));
    }
}
