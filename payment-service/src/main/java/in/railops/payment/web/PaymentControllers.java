package in.railops.payment.web;

import in.railops.payment.gateway.GatewayPort;
import in.railops.payment.gateway.SimulatedGateway;
import in.railops.payment.gateway.WebhookSignature;
import in.railops.payment.service.PaymentService;
import in.railops.payment.service.PaymentService.CreateRequest;
import in.railops.payment.service.PaymentService.Intent;
import in.railops.payment.service.WebhookService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public final class PaymentControllers {

    private PaymentControllers() {
    }

    /** Service-to-service only: SecurityConfig requires audience railops-internal and SCOPE_payments:create. */
    @RestController
    public static class InternalPayments {

        private final PaymentService payments;

        public InternalPayments(PaymentService payments) {
            this.payments = payments;
        }

        @PostMapping("/internal/v1/payments")
        public ResponseEntity<Intent> create(@RequestBody CreateRequest req) {
            return ResponseEntity.status(HttpStatus.CREATED).body(payments.createOrGet(req));
        }

        /**
         * Synchronous status, for when the event path is degraded (Phase 13, INC-001: a Kafka partition kept
         * PaymentCaptured in our outbox while reservation expired the customer's hold).
         */
        @org.springframework.web.bind.annotation.GetMapping("/internal/v1/payments/status")
        public java.util.List<PaymentService.StatusView> status(
                @org.springframework.web.bind.annotation.RequestParam java.util.List<java.util.UUID> ids) {
            if (ids.size() > 200) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "at most 200 ids");
            }
            return payments.statuses(ids);
        }
    }

    /**
     * Gateway webhooks. Body taken as raw bytes: the signature covers exactly what was sent.
     * 200 for duplicates (the gateway must stop retrying), 401 for bad signatures, 500 on processing errors
     * (so the gateway retries: e.g. a webhook racing our own commit of the order id).
     */
    @RestController
    public static class Webhooks {

        private static final Logger log = LoggerFactory.getLogger(Webhooks.class);

        private final WebhookService webhooks;
        private final WebhookSignature signature;
        private final GatewayPort gateway;
        private final MeterRegistry meters;

        public Webhooks(WebhookService webhooks, WebhookSignature signature, GatewayPort gateway, MeterRegistry meters) {
            this.webhooks = webhooks;
            this.signature = signature;
            this.gateway = gateway;
            this.meters = meters;
        }

        @PostMapping("/webhooks/{gw}")
        public ResponseEntity<Void> receive(@PathVariable String gw,
                                            @RequestHeader(name = "X-Sim-Timestamp", required = false) String ts,
                                            @RequestHeader(name = "X-Sim-Signature", required = false) String sig,
                                            @RequestBody byte[] raw) throws Exception {
            if (!gateway.name().equals(gw) || !signature.verify(ts, sig, raw, Instant.now())) {
                meters.counter("payment.webhook.rejected").increment();
                log.warn("rejected webhook for gateway '{}' (bad or stale signature)", gw);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            webhooks.apply(gw, raw);
            return ResponseEntity.ok().build();
        }
    }

    /**
     * SIM PROFILE ONLY (Phase 5): reservation-service, on behalf of the passenger who owns the booking, plays the
     * customer at the simulated checkout. Service token with payments:create; no passenger can call it directly.
     * The normal webhook then flows back exactly as with a real gateway.
     */
    @RestController
    @Profile("sim")
    public static class InternalSimCheckout {

        private final SimulatedGateway gateway;
        private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

        public InternalSimCheckout(SimulatedGateway gateway, org.springframework.jdbc.core.simple.JdbcClient jdbc) {
            this.gateway = gateway;
            this.jdbc = jdbc;
        }

        record Row(java.util.UUID id, BigDecimal amount, String status, String orderId) {
        }

        @PostMapping("/internal/v1/sim/payments/{paymentId}/pay")
        public ResponseEntity<Map<String, String>> pay(@PathVariable java.util.UUID paymentId,
                                                       @RequestParam(defaultValue = "success") String outcome) {
            if (!outcome.equals("success") && !outcome.equals("failure")) {
                return ResponseEntity.badRequest().body(Map.of("code", "BAD_OUTCOME"));
            }
            Row row = jdbc.sql("SELECT id, amount, status, gateway_order_id FROM payment.payment WHERE id = :id")
                    .param("id", paymentId)
                    .query((rs, n) -> new Row(rs.getObject("id", java.util.UUID.class), rs.getBigDecimal("amount"),
                            rs.getString("status"), rs.getString("gateway_order_id")))
                    .optional().orElse(null);
            if (row == null || row.orderId() == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "PAYMENT_NOT_FOUND"));
            }
            if (!row.status().equals("PENDING")) {
                // Already decided: the outcome stands (a second "pay" must not capture twice).
                return ResponseEntity.ok(Map.of("paymentId", paymentId.toString(), "status", row.status()));
            }
            gateway.adopt(row.orderId(), row.id(), row.amount());
            gateway.customerPays(row.orderId(), outcome, null, 1, false);
            return ResponseEntity.accepted().body(Map.of("paymentId", paymentId.toString(), "outcome", outcome));
        }
    }

    /**
     * Razorpay webhooks (Phase 6, "razorpay" profile). More specific than /webhooks/{gw}, so Spring routes here.
     * 401 on a bad signature; 200 for duplicates; 500 on processing errors so Razorpay retries.
     */
    @RestController
    @Profile("razorpay")
    public static class RazorpayWebhooks {

        private static final Logger log = LoggerFactory.getLogger(RazorpayWebhooks.class);

        private final in.railops.payment.gateway.RazorpayGateway razorpay;
        private final WebhookService webhooks;
        private final MeterRegistry meters;

        public RazorpayWebhooks(in.railops.payment.gateway.RazorpayGateway razorpay, WebhookService webhooks, MeterRegistry meters) {
            this.razorpay = razorpay;
            this.webhooks = webhooks;
            this.meters = meters;
        }

        @PostMapping("/webhooks/razorpay")
        public ResponseEntity<Void> receive(@RequestHeader(name = "X-Razorpay-Signature", required = false) String sig,
                                            @RequestHeader(name = "X-Razorpay-Event-Id", required = false) String eventId,
                                            @RequestBody byte[] raw) throws Exception {
            if (!razorpay.verifySignature(sig, raw)) {
                meters.counter("payment.webhook.rejected").increment();
                log.warn("rejected Razorpay webhook (bad signature)");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            webhooks.apply("razorpay", razorpay.normalize(eventId, raw), raw);
            return ResponseEntity.ok().build();
        }
    }

    /** LOCAL PROFILE ONLY: plays the passenger at the gateway's checkout page. */
    @RestController
    @Profile("sim")
    public static class SimGateway {

        private final SimulatedGateway gateway;

        public SimGateway(SimulatedGateway gateway) {
            this.gateway = gateway;
        }

        @PostMapping("/sim-gateway/orders/{orderId}/pay")
        public Map<String, String> pay(@PathVariable String orderId,
                                       @RequestParam(defaultValue = "success") String outcome,
                                       @RequestParam(required = false) BigDecimal capturedAmount,
                                       @RequestParam(defaultValue = "1") int deliveries,
                                       @RequestParam(defaultValue = "false") boolean suppressWebhook) {
            gateway.customerPays(orderId, outcome, capturedAmount, deliveries, suppressWebhook);
            return Map.of("orderId", orderId, "outcome", outcome);
        }
    }
}
