package in.railops.payment.service;

import tools.jackson.databind.ObjectMapper;
import in.railops.payment.gateway.GatewayPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * PAY-4 safety net for webhooks that never arrive. Payments PENDING for longer than {@code staleAfter} are
 * looked up at the gateway; a capture found there is applied through the SAME code path as a webhook, with a
 * synthetic event id ("recon:" + order id), so a late real webhook for it is deduped instead of double-posted.
 */
@Service
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    private final JdbcClient jdbc;
    private final GatewayPort gateway;
    private final WebhookService webhooks;
    private final ObjectMapper mapper;
    private final Duration staleAfter;

    public Reconciler(JdbcClient jdbc, GatewayPort gateway, WebhookService webhooks, ObjectMapper mapper,
                      @Value("${railops.reconcile.stale-after:PT15M}") Duration staleAfter) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.webhooks = webhooks;
        this.mapper = mapper;
        this.staleAfter = staleAfter;
    }

    public int run() {
        List<String> orders = jdbc.sql("""
                        SELECT gateway_order_id FROM payment.payment
                        WHERE status = 'PENDING' AND gateway_order_id IS NOT NULL
                          AND updated_at < now() - CAST(:age AS interval)
                        ORDER BY updated_at LIMIT 100
                        """).param("age", staleAfter.toSeconds() + " seconds").query(String.class).list();
        int fixed = 0;
        for (String orderId : orders) {
            try {
                GatewayPort.OrderStatus st = gateway.fetchOrder(orderId);
                String type = switch (st.status()) {
                    case "captured" -> "payment.captured";
                    case "failed" -> "payment.failed";
                    default -> null;
                };
                if (type == null) {
                    continue;
                }
                byte[] synthetic = mapper.writeValueAsBytes(Map.of("id", "recon:" + orderId, "type", type,
                        "data", Map.of("orderId", orderId, "paymentId", String.valueOf(st.gatewayPaymentId()),
                                "amount", st.capturedAmount().toPlainString(), "currency", "INR")));
                if (webhooks.apply(gateway.name(), synthetic)) {
                    fixed++;
                    log.warn("reconciliation applied missed {} for order {}", type, orderId);
                }
            } catch (Exception e) {
                log.warn("reconciliation of {} failed: {}", orderId, e.toString());
            }
        }
        return fixed;
    }
}
