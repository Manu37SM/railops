package in.railops.payment.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.railops.payment.gateway.GatewayPort;
import in.railops.payment.gateway.SimulatedGateway;
import in.railops.payment.gateway.WebhookSignature;
import in.railops.payment.service.PaymentService;
import in.railops.payment.service.RefundService;
import in.railops.payment.service.RefundService.RefundRequested;
import in.railops.testsupport.EventContracts;
import in.railops.testsupport.ItPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MONEY invariants of payment-service on real PostgreSQL:
 *  - a refund budget that holds under concurrency (sum of live refunds <= captured);
 *  - a ledger that cannot be unbalanced or edited, enforced by the database;
 *  - webhooks applied exactly once, and only when correctly signed;
 *  - every event leaving through the outbox honours its contract.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PaymentMoneyIT {

    static final ItPostgres.Db DB = ItPostgres.newDatabase();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::url);
        r.add("spring.datasource.username", DB::user);
        r.add("spring.datasource.password", DB::password);
        r.add("railops.scheduling.enabled", () -> "false");
        r.add("spring.kafka.listener.auto-startup", () -> "false");
        r.add("spring.kafka.admin.auto-create", () -> "false");
        r.add("spring.kafka.bootstrap-servers", () -> "127.0.0.1:1");
    }

    @MockitoBean SimulatedGateway gateway;   // the external PSP: the one thing we don't own
    @Autowired PaymentService payments;
    @Autowired RefundService refunds;
    @Autowired WebhookSignature signer;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE payment.refund, payment.webhook_event, payment.outbox, payment.processed_event, payment.payment CASCADE").update();
        // ledger_entry is append-only by trigger; tests use fresh payment ids so leftovers never interfere.
        when(gateway.name()).thenReturn("simgw");
        when(gateway.createOrder(any(), any(), anyString(), anyString()))
                .thenAnswer(i -> {
                    String id = "ord_" + UUID.randomUUID();
                    return new GatewayPort.Order(id, "http://gw/checkout/" + id);
                });
        when(gateway.checkoutUrl(anyString())).thenAnswer(i -> "http://gw/checkout/" + i.getArgument(0));
    }

    // ------------------------------------------------------------------ Phase 15: one gateway order per payment

    static String pnr() {
        return "3" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
    }

    @Test
    void retrying_pay_now_reuses_the_order_even_when_the_gateway_does_not_dedupe() {
        // The mock gateway returns a NEW order id per call, like real order APIs. Before Phase 15 every retry
        // created and stored a new order: a customer paying on the first checkout page was captured on an order
        // payment-service no longer knew (webhook -> "unknown gateway order" forever, booking never confirmed).
        var req = new PaymentService.CreateRequest(pnr(), UUID.randomUUID(), new BigDecimal("845.00"), "INR",
                Instant.now().plusSeconds(600));
        var first = payments.createOrGet(req);
        var second = payments.createOrGet(req);
        assertThat(second.paymentId()).isEqualTo(first.paymentId());
        assertThat(second.checkoutUrl()).as("same checkout page, same order").isEqualTo(first.checkoutUrl());
        verify(gateway, times(1)).createOrder(any(), any(), anyString(), anyString());
    }

    @Test
    void concurrent_pay_now_for_one_pnr_yields_one_payment_and_one_order_without_errors() throws Exception {
        var req = new PaymentService.CreateRequest(pnr(), UUID.randomUUID(), new BigDecimal("845.00"), "INR",
                Instant.now().plusSeconds(600));
        int n = 8;
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(n);
        List<Future<PaymentService.Intent>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                start.await();
                return payments.createOrGet(req);
            }));
        }
        start.countDown();
        Set<UUID> ids = new HashSet<>();
        Set<String> urls = new HashSet<>();
        for (var f : fs) {
            var intent = f.get(20, TimeUnit.SECONDS);   // before: the losing INSERT's aborted tx -> 25P02 -> exception
            ids.add(intent.paymentId());
            urls.add(intent.checkoutUrl());
        }
        pool.shutdown();
        assertThat(ids).hasSize(1);
        assertThat(urls).as("every caller is sent to the one bound order").hasSize(1);
        String bound = jdbc.sql("SELECT gateway_order_id FROM payment.payment WHERE pnr = :p").param("p", req.pnr())
                .query(String.class).single();
        assertThat(urls).containsExactly("http://gw/checkout/" + bound);
    }

    // ------------------------------------------------------------------------------------------------- helpers

    record Captured(UUID paymentId, String pnr, BigDecimal amount) {
    }

    Captured capturedPayment(String amount) throws Exception {
        String pnr = "2" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
        var intent = payments.createOrGet(new PaymentService.CreateRequest(pnr, UUID.randomUUID(), new BigDecimal(amount),
                "INR", Instant.now().plusSeconds(600)));
        String order = jdbc.sql("SELECT gateway_order_id FROM payment.payment WHERE id = :id").param("id", intent.paymentId())
                .query(String.class).single();
        webhook(Map.of("id", "evt_" + UUID.randomUUID(), "type", "payment.captured",
                "data", Map.of("orderId", order, "paymentId", "pay_" + UUID.randomUUID(), "amount", amount, "currency", "INR")), true);
        return new Captured(intent.paymentId(), pnr, new BigDecimal(amount));
    }

    int webhook(Map<String, Object> event, boolean signed) throws Exception {
        byte[] raw = json.writeValueAsBytes(event);
        long ts = Instant.now().getEpochSecond();
        return mvc.perform(post("/webhooks/simgw").contentType(MediaType.APPLICATION_JSON).content(raw)
                        .header("X-Sim-Timestamp", Long.toString(ts))
                        .header("X-Sim-Signature", signed ? signer.sign(ts, raw) : "deadbeef"))
                .andReturn().getResponse().getStatus();
    }

    void assertLedgerBalanced() {
        List<Map<String, Object>> unbalanced = jdbc.sql("""
                SELECT txn_id, sum(CASE direction WHEN 'D' THEN amount ELSE -amount END) AS net
                FROM payment.ledger_entry GROUP BY txn_id HAVING sum(CASE direction WHEN 'D' THEN amount ELSE -amount END) <> 0
                """).query().listOfRows();
        assertThat(unbalanced).isEmpty();
    }

    void assertOutboxHonoursContracts() {
        var rows = jdbc.sql("SELECT event_type, payload::text FROM payment.outbox")
                .query((rs, i) -> new String[]{rs.getString(1), rs.getString(2)}).list();
        assertThat(rows).isNotEmpty();
        rows.forEach(r -> assertThat(EventContracts.validate(r[0], 1, r[1])).as(r[0] + " " + r[1]).isEmpty());
    }

    // ------------------------------------------------------------------------------------------------- webhooks

    @Test
    void a_signed_capture_is_applied_once_and_posted_to_a_balanced_ledger() throws Exception {
        Captured c = capturedPayment("2915.00");
        assertThat(jdbc.sql("SELECT status FROM payment.payment WHERE id = :id").param("id", c.paymentId())
                .query(String.class).single()).isEqualTo("CAPTURED");
        assertThat(jdbc.sql("SELECT count(*) FROM payment.outbox WHERE event_type='PaymentCaptured'").query(Integer.class).single()).isEqualTo(1);
        assertLedgerBalanced();
        assertOutboxHonoursContracts();
    }

    @Test
    void a_replayed_webhook_is_acknowledged_but_not_applied_twice() throws Exception {
        Captured c = capturedPayment("500.00");
        String order = jdbc.sql("SELECT gateway_order_id FROM payment.payment WHERE id = :id").param("id", c.paymentId()).query(String.class).single();
        String evt = jdbc.sql("SELECT gateway_event_id FROM payment.webhook_event").query(String.class).single();
        int code = webhook(Map.of("id", evt, "type", "payment.captured",
                "data", Map.of("orderId", order, "paymentId", "pay_x", "amount", "500.00", "currency", "INR")), true);
        assertThat(code).as("200 so the gateway stops retrying").isEqualTo(200);
        assertThat(jdbc.sql("SELECT count(*) FROM payment.ledger_entry WHERE payment_id = :p").param("p", c.paymentId())
                .query(Integer.class).single()).isEqualTo(2);   // one capture txn = 2 legs
    }

    @Test
    void an_unsigned_or_stale_webhook_is_rejected_and_changes_nothing() throws Exception {
        var intent = payments.createOrGet(new PaymentService.CreateRequest("2400000018", UUID.randomUUID(),
                new BigDecimal("100.00"), "INR", Instant.now().plusSeconds(600)));
        String order = jdbc.sql("SELECT gateway_order_id FROM payment.payment WHERE id = :id").param("id", intent.paymentId()).query(String.class).single();
        Map<String, Object> forged = Map.of("id", "evt_forged", "type", "payment.captured",
                "data", Map.of("orderId", order, "paymentId", "pay_x", "amount", "100.00", "currency", "INR"));
        assertThat(webhook(forged, false)).isEqualTo(401);

        byte[] raw = json.writeValueAsBytes(forged);
        long old = Instant.now().minusSeconds(3600).getEpochSecond();    // valid signature, replayed an hour later
        int stale = mvc.perform(post("/webhooks/simgw").contentType(MediaType.APPLICATION_JSON).content(raw)
                .header("X-Sim-Timestamp", Long.toString(old)).header("X-Sim-Signature", signer.sign(old, raw)))
                .andReturn().getResponse().getStatus();
        assertThat(stale).isEqualTo(401);
        assertThat(jdbc.sql("SELECT status FROM payment.payment WHERE id = :id").param("id", intent.paymentId())
                .query(String.class).single()).isEqualTo("PENDING");
    }

    // --------------------------------------------------------------------------------------------- refund budget

    @Test
    void concurrent_refund_requests_can_never_exceed_the_capture() throws Exception {
        Captured c = capturedPayment("1000.00");
        int n = 12;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CyclicBarrier go = new CyclicBarrier(n);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = i;
            fs.add(pool.submit(() -> {
                go.await();
                refunds.accept(UUID.randomUUID(), new RefundRequested(c.pnr(), c.paymentId(), new BigDecimal("400.00"),
                        "USER_CANCELLATION", "cancel:" + k));
                return null;
            }));
        }
        for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        BigDecimal live = jdbc.sql("""
                SELECT COALESCE(sum(amount),0) FROM payment.refund WHERE payment_id = :p
                AND status IN ('REQUESTED','SUBMITTING','SUBMITTED','SUCCEEDED','FAILED')""")
                .param("p", c.paymentId()).query(BigDecimal.class).single();
        assertThat(live).isEqualByComparingTo("800.00");          // 2 x 400 fit in 1000; a third would not
        assertThat(jdbc.sql("SELECT count(*) FROM payment.refund WHERE status = 'MANUAL_REVIEW'")
                .query(Integer.class).single()).isEqualTo(n - 2); // the rest are held for a human, not dropped
    }

    @Test
    void the_same_refund_redelivered_with_new_event_ids_is_recorded_once() throws Exception {
        Captured c = capturedPayment("1000.00");
        for (int i = 0; i < 3; i++) {
            refunds.accept(UUID.randomUUID(), new RefundRequested(c.pnr(), c.paymentId(), new BigDecimal("300.00"),
                    "USER_CANCELLATION", "cancel:same"));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM payment.refund").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void a_refund_for_an_uncaptured_payment_is_held_for_review() {
        var intent = payments.createOrGet(new PaymentService.CreateRequest("2400000026", UUID.randomUUID(),
                new BigDecimal("100.00"), "INR", Instant.now().plusSeconds(600)));
        refunds.accept(UUID.randomUUID(), new RefundRequested("2400000026", intent.paymentId(), new BigDecimal("100.00"),
                "USER_CANCELLATION", "cancel:uncaptured"));
        assertThat(jdbc.sql("SELECT status FROM payment.refund").query(String.class).single()).isEqualTo("MANUAL_REVIEW");
    }

    // ------------------------------------------------------------------------------------- refund to settlement

    @Test
    void refund_is_submitted_settled_by_webhook_and_posted_to_the_ledger() throws Exception {
        Captured c = capturedPayment("1000.00");
        refunds.accept(UUID.randomUUID(), new RefundRequested(c.pnr(), c.paymentId(), new BigDecimal("950.00"),
                "USER_CANCELLATION", "cancel:settle"));
        when(gateway.refund(anyString(), any(), any())).thenReturn(new GatewayPort.RefundAck("rfnd_1"));
        assertThat(refunds.submitDue(10)).isEqualTo(1);
        assertThat(refunds.submitDue(10)).as("not resubmitted while SUBMITTED").isZero();
        verify(gateway, times(1)).refund(anyString(), any(), eq(new BigDecimal("950.00")));

        webhook(Map.of("id", "evt_r1", "type", "refund.processed", "data", Map.of("refundId", "rfnd_1")), true);
        webhook(Map.of("id", "evt_r1b", "type", "refund.processed", "data", Map.of("refundId", "rfnd_1")), true);   // dup

        assertThat(jdbc.sql("SELECT status FROM payment.refund").query(String.class).single()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.sql("SELECT count(*) FROM payment.ledger_entry WHERE refund_id IS NOT NULL").query(Integer.class).single())
                .as("one refund posting (2 legs), not two").isEqualTo(2);
        BigDecimal payable = jdbc.sql("""
                SELECT sum(CASE direction WHEN 'C' THEN amount ELSE -amount END) FROM payment.ledger_entry
                WHERE account = 'CUSTOMER_PAYABLE' AND payment_id = :p""").param("p", c.paymentId()).query(BigDecimal.class).single();
        assertThat(payable).isEqualByComparingTo("50.00");   // 1000 captured - 950 refunded
        assertLedgerBalanced();
        assertOutboxHonoursContracts();
    }

    @Test
    void a_refund_that_keeps_failing_ends_in_manual_review_with_an_alert_event() throws Exception {
        Captured c = capturedPayment("1000.00");
        refunds.accept(UUID.randomUUID(), new RefundRequested(c.pnr(), c.paymentId(), new BigDecimal("100.00"),
                "USER_CANCELLATION", "cancel:fails"));
        when(gateway.refund(anyString(), any(), any())).thenThrow(new IllegalStateException("gateway 503"));
        for (int i = 0; i < 10; i++) {
            jdbc.sql("UPDATE payment.refund SET next_attempt_at = now() - interval '1 second' WHERE status IN ('FAILED','REQUESTED')").update();
            refunds.submitDue(10);
        }
        assertThat(jdbc.sql("SELECT status FROM payment.refund").query(String.class).single()).isEqualTo("MANUAL_REVIEW");
        assertThat(jdbc.sql("SELECT count(*) FROM payment.outbox WHERE event_type='RefundNeedsReview'").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT DISTINCT aggregate_id FROM payment.outbox WHERE topic = 'payment.events.v1'")
                .query(String.class).list()).as("every payment event for this PNR shares its Kafka key")
                .containsExactly(c.pnr());
        assertOutboxHonoursContracts();
    }

    // ---------------------------------------------------------------------------------- ledger, by the database

    @Test
    void an_unbalanced_ledger_transaction_cannot_commit() {
        UUID txn = UUID.randomUUID();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.sql("""
                INSERT INTO payment.ledger_entry (txn_id, account, direction, amount, memo)
                VALUES (:t, 'GATEWAY_CLEARING', 'D', 100.00, 'test')""").param("t", txn).update()))
                .hasStackTraceContaining("unbalanced");   // raised at COMMIT by the deferred trigger, wrapped by Spring
        assertThat(jdbc.sql("SELECT count(*) FROM payment.ledger_entry WHERE txn_id = :t").param("t", txn)
                .query(Integer.class).single()).isZero();
    }

    @Test
    void ledger_rows_cannot_be_edited_or_deleted() throws Exception {
        capturedPayment("100.00");
        assertThatThrownBy(() -> jdbc.sql("UPDATE payment.ledger_entry SET amount = 1").update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM payment.ledger_entry").update()).isInstanceOf(Exception.class);
    }
}
