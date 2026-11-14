package in.railops.payment.gateway;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * LOCAL PROFILE ONLY. Behaves like a real gateway where it matters for testing our side:
 * idempotent order creation and refunds, ASYNC signed webhooks (refunds settle later, as in reality),
 * and failure modes on demand (declines, duplicate webhooks, a captured amount that differs from the order,
 * webhooks that never arrive). A production adapter implements the same {@link GatewayPort}.
 */
@Component
@Profile("sim")
public class SimulatedGateway implements GatewayPort {

    private static final Logger log = LoggerFactory.getLogger(SimulatedGateway.class);

    public static final class SimOrder {
        final String orderId;
        final UUID paymentId;
        final BigDecimal amount;
        volatile String status = "created";
        volatile BigDecimal captured = BigDecimal.ZERO;
        volatile String gatewayPaymentId;

        SimOrder(String orderId, UUID paymentId, BigDecimal amount) {
            this.orderId = orderId;
            this.paymentId = paymentId;
            this.amount = amount;
        }
    }

    private final Map<UUID, SimOrder> byPayment = new ConcurrentHashMap<>();
    private final Map<String, SimOrder> byOrder = new ConcurrentHashMap<>();
    private final Map<UUID, String> refunds = new ConcurrentHashMap<>();
    private final ScheduledExecutorService async = Executors.newSingleThreadScheduledExecutor();
    private final WebhookSignature signer;
    private final ObjectMapper mapper;
    private final RestClient self;

    public SimulatedGateway(WebhookSignature signer, ObjectMapper mapper, RestClient.Builder builder,
                            @Value("${railops.sim-gateway.webhook-url}") String webhookUrl) {
        this.signer = signer;
        this.mapper = mapper;
        this.self = builder.baseUrl(webhookUrl).build();
    }

    @Override
    public String name() {
        return "simgw";
    }

    @Override
    public Order createOrder(UUID paymentId, BigDecimal amount, String currency, String pnr) {
        SimOrder o = byPayment.computeIfAbsent(paymentId, id -> {
            SimOrder n = new SimOrder("order_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14), id, amount);
            byOrder.put(n.orderId, n);
            return n;
        });
        return new Order(o.orderId, checkoutUrl(o.orderId));
    }

    @Override
    public String checkoutUrl(String orderId) {
        return "https://pay.example/checkout/" + orderId;
    }

    @Override
    public OrderStatus fetchOrder(String orderId) {
        SimOrder o = byOrder.get(orderId);
        return o == null ? new OrderStatus(orderId, "unknown", BigDecimal.ZERO, null)
                : new OrderStatus(orderId, o.status, o.captured, o.gatewayPaymentId);
    }

    @Override
    public RefundAck refund(String orderId, UUID refundId, BigDecimal amount) {
        String id = refunds.computeIfAbsent(refundId, r -> {
            String gid = "rfnd_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
            // Refunds settle asynchronously, like a real gateway: the webhook arrives a moment later.
            async.schedule(() -> sendWebhook("refund.processed", Map.of("orderId", orderId, "refundId", gid,
                    "amount", amount.toPlainString())), 300, TimeUnit.MILLISECONDS);
            return gid;
        });
        return new RefundAck(id);
    }

    /**
     * Orders live in memory, so a restart (Render's free plan sleeps) forgets them while the payment row still names
     * the order. Re-registers it so the passenger can still finish paying (Phase 5).
     */
    public void adopt(String orderId, UUID paymentId, BigDecimal amount) {
        byOrder.computeIfAbsent(orderId, id -> {
            SimOrder o = new SimOrder(id, paymentId, amount);
            byPayment.putIfAbsent(paymentId, o);
            return o;
        });
    }

    /** Simulates the customer completing (or failing) checkout. Driven by SimGatewayController. */
    public void customerPays(String orderId, String outcome, BigDecimal capturedOverride, int deliveries, boolean suppressWebhook) {
        SimOrder o = byOrder.get(orderId);
        if (o == null) {
            throw new IllegalArgumentException("unknown order " + orderId);
        }
        if ("success".equals(outcome)) {
            o.status = "captured";
            o.captured = capturedOverride != null ? capturedOverride : o.amount;
            o.gatewayPaymentId = "pay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        } else {
            o.status = "failed";
        }
        if (suppressWebhook) {
            log.info("sim-gateway: {} {} with webhook SUPPRESSED (reconciliation must catch it)", orderId, outcome);
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", orderId);
        data.put("paymentId", o.gatewayPaymentId);
        data.put("amount", o.captured.toPlainString());
        data.put("currency", "INR");
        String eventId = "evt_" + UUID.randomUUID();
        for (int i = 0; i < Math.max(1, deliveries); i++) {
            sendWebhook("success".equals(outcome) ? "payment.captured" : "payment.failed", data, eventId);
        }
    }

    private void sendWebhook(String type, Map<String, Object> data) {
        sendWebhook(type, data, "evt_" + UUID.randomUUID());
    }

    private void sendWebhook(String type, Map<String, Object> data, String eventId) {
        try {
            byte[] body = mapper.writeValueAsBytes(Map.of("id", eventId, "type", type, "data", data));
            long ts = Instant.now().getEpochSecond();
            self.post().contentType(MediaType.APPLICATION_JSON)
                    .header("X-Sim-Timestamp", Long.toString(ts))
                    .header("X-Sim-Signature", signer.sign(ts, body))
                    .body(body).retrieve().toBodilessEntity();
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        } catch (RuntimeException e) {
            log.warn("sim-gateway: webhook delivery failed ({}); a real gateway would retry", e.toString());
        }
    }
}
