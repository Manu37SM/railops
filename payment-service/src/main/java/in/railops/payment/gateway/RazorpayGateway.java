package in.railops.payment.gateway;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Razorpay, TEST MODE (Phase 6). Uses Payment Links: a hosted page at {@code rzp.io}, so the website and the app only
 * need to open a URL (and allow that host); no Razorpay script runs on our pages.
 *
 * <ul>
 *   <li>Our "order id" is the Payment Link id ({@code plink_...}). {@code reference_id} = our payment id.</li>
 *   <li>Amounts travel in paise. The amount is always the server-side fare from payment.payment.</li>
 *   <li>Webhooks: {@code X-Razorpay-Signature} = hex HMAC-SHA256(webhook secret, raw body), checked in constant time
 *   by {@link #verifySignature}. {@code X-Razorpay-Event-Id} is the dedupe key.</li>
 *   <li>Refunds: Razorpay doesn't promise to dedupe on our receipt, so before refunding we look for a refund that
 *   already carries this refund id (a retry after a timeout must never refund twice).</li>
 * </ul>
 *
 * <p>Refuses to start with live keys ({@code rzp_live_...}): RailOps can't sell real train tickets (that needs IRCTC
 * authorisation), so real money must never move through it.
 */
@Component
@Profile("razorpay")
public class RazorpayGateway implements GatewayPort {

    /** Razorpay requires a link to stay open at least 15 minutes; the seat hold (10 min) is enforced by reservation. */
    static final Duration LINK_TTL = Duration.ofMinutes(20);

    private final RestClient http;
    private final ObjectMapper mapper;
    private final byte[] webhookSecret;
    private final String callbackTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    public RazorpayGateway(RestClient.Builder builder, ObjectMapper mapper,
                           @Value("${railops.razorpay.base-url:https://api.razorpay.com/v1}") String baseUrl,
                           @Value("${railops.razorpay.key-id}") String keyId,
                           @Value("${railops.razorpay.key-secret}") String keySecret,
                           @Value("${railops.razorpay.webhook-secret}") String webhookSecret,
                           @Value("${railops.razorpay.callback-url:}") String callbackTemplate) {
        this(builder.requestFactory(timeouts()), mapper, baseUrl, keyId, keySecret, webhookSecret, callbackTemplate, true);
    }

    /** Tests pass a builder bound to a mock server; production adds timeouts above. */
    RazorpayGateway(RestClient.Builder builder, ObjectMapper mapper, String baseUrl, String keyId, String keySecret,
                    String webhookSecret, String callbackTemplate, boolean unused) {
        if (!keyId.startsWith("rzp_test_")) {
            throw new IllegalStateException("railops.razorpay.key-id must be a TEST key (rzp_test_...): RailOps never takes real money");
        }
        if (webhookSecret == null || webhookSecret.length() < 16) {
            throw new IllegalStateException("railops.razorpay.webhook-secret must be at least 16 characters");
        }
        String basic = Base64.getEncoder().encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
        this.http = builder.baseUrl(baseUrl).defaultHeader("Authorization", "Basic " + basic).build();
        this.mapper = mapper;
        this.webhookSecret = webhookSecret.getBytes(StandardCharsets.UTF_8);
        this.callbackTemplate = callbackTemplate == null ? "" : callbackTemplate.trim();
    }

    private static SimpleClientHttpRequestFactory timeouts() {
        var rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(3));
        rf.setReadTimeout(Duration.ofSeconds(10));
        return rf;
    }

    @Override
    public String name() {
        return "razorpay";
    }

    static long paise(BigDecimal rupees) {
        return rupees.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }

    static BigDecimal rupees(long paise) {
        return BigDecimal.valueOf(paise).movePointLeft(2);
    }

    @Override
    public Order createOrder(UUID paymentId, BigDecimal amount, String currency, String pnr) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", paise(amount));
        body.put("currency", currency);
        body.put("accept_partial", false);
        body.put("reference_id", paymentId.toString());
        body.put("description", "RailOps demo ticket, PNR " + pnr + " (test mode, no real money)");
        body.put("expire_by", Instant.now().plus(LINK_TTL).getEpochSecond());
        body.put("reminder_enable", false);
        body.put("notify", Map.of("sms", false, "email", false));   // no personal data sent to Razorpay
        body.put("notes", Map.of("pnr", pnr, "payment_id", paymentId.toString()));
        if (!callbackTemplate.isEmpty()) {
            body.put("callback_url", callbackTemplate.replace("{pnr}", pnr));
            body.put("callback_method", "get");
        }
        JsonNode link = http.post().uri("/payment_links").contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(JsonNode.class);
        String id = link == null ? "" : link.path("id").asString();
        String url = link == null ? "" : link.path("short_url").asString();
        if (!id.startsWith("plink_") || !url.startsWith("https://")) {
            throw new IllegalStateException("Razorpay returned an unexpected payment link");
        }
        if (link.path("amount").asLong(-1) != paise(amount)) {
            throw new IllegalStateException("Razorpay payment link amount differs from the fare");
        }
        return new Order(id, url);
    }

    @Override
    public String checkoutUrl(String orderId) {
        return fetchLink(orderId).path("short_url").asString();
    }

    @Override
    public OrderStatus fetchOrder(String orderId) {
        JsonNode link = fetchLink(orderId);
        String status = link.path("status").asString();
        JsonNode captured = null;
        for (JsonNode p : link.path("payments")) {
            if ("captured".equals(p.path("status").asString())) {
                captured = p;
            }
        }
        if ("paid".equals(status) && captured != null) {
            return new OrderStatus(orderId, "captured", rupees(link.path("amount_paid").asLong()), captured.path("payment_id").asString());
        }
        if ("expired".equals(status) || "cancelled".equals(status)) {
            return new OrderStatus(orderId, "failed", BigDecimal.ZERO, null);
        }
        return new OrderStatus(orderId, "created", BigDecimal.ZERO, null);
    }

    @Override
    public RefundAck refund(String orderId, UUID refundId, BigDecimal amount) {
        OrderStatus st = fetchOrder(orderId);
        if (!"captured".equals(st.status()) || st.gatewayPaymentId() == null) {
            throw new IllegalStateException("nothing captured to refund on " + orderId);
        }
        String paymentId = st.gatewayPaymentId();
        JsonNode existing = http.get().uri("/payments/{id}/refunds", paymentId).retrieve().body(JsonNode.class);
        if (existing != null) {
            for (JsonNode r : existing.path("items")) {
                if (refundId.toString().equals(r.path("receipt").asString())) {
                    return new RefundAck(r.path("id").asString());   // a retry: the first attempt did reach Razorpay
                }
            }
        }
        JsonNode r = http.post().uri("/payments/{id}/refunds", paymentId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", paise(amount), "speed", "normal", "receipt", refundId.toString(),
                        "notes", Map.of("refund_id", refundId.toString())))
                .retrieve().body(JsonNode.class);
        String id = r == null ? "" : r.path("id").asString();
        if (!id.startsWith("rfnd_")) {
            throw new IllegalStateException("Razorpay returned an unexpected refund");
        }
        return new RefundAck(id);
    }

    private JsonNode fetchLink(String orderId) {
        if (!orderId.matches("plink_[A-Za-z0-9]{1,40}")) {
            throw new IllegalArgumentException("not a Razorpay payment link id");
        }
        JsonNode link = http.get().uri("/payment_links/{id}", orderId).retrieve().body(JsonNode.class);
        if (link == null || !orderId.equals(link.path("id").asString())) {
            throw new IllegalStateException("Razorpay returned a different payment link");
        }
        return link;
    }

    // ------------------------------------------------------------------ webhooks

    public boolean verifySignature(String signatureHeader, byte[] rawBody) {
        if (signatureHeader == null || !signatureHeader.matches("[0-9a-f]{64}")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret, "HmacSHA256"));
            byte[] expected = HexFormat.of().formatHex(mac.doFinal(rawBody)).getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(expected, signatureHeader.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Razorpay event → the internal event format WebhookService applies. Unknown events become "ignored". Payment
     * failures on a link are NOT final (the passenger can retry on the same page), so only an expired or cancelled
     * link fails the payment.
     */
    public byte[] normalize(String eventIdHeader, byte[] rawBody) throws java.io.IOException {
        JsonNode e = mapper.readTree(rawBody);
        String event = e.path("event").asString();
        JsonNode payload = e.path("payload");
        String eventId = eventIdHeader != null && !eventIdHeader.isBlank() ? eventIdHeader
                : "sha256:" + HexFormat.of().formatHex(sha256(rawBody));
        Map<String, Object> data = new LinkedHashMap<>();
        String type;
        switch (event) {
            case "payment_link.paid" -> {
                JsonNode link = payload.path("payment_link").path("entity");
                JsonNode pay = payload.path("payment").path("entity");
                type = "payment.captured";
                data.put("orderId", link.path("id").asString());
                data.put("paymentId", pay.path("id").asString());
                data.put("amount", rupees(pay.path("amount").asLong(link.path("amount_paid").asLong())).toPlainString());
                data.put("currency", pay.path("currency").asString(link.path("currency").asString("INR")));
            }
            case "payment_link.expired", "payment_link.cancelled" -> {
                type = "payment.failed";
                data.put("orderId", payload.path("payment_link").path("entity").path("id").asString());
            }
            case "refund.processed" -> {
                type = "refund.processed";
                data.put("refundId", payload.path("refund").path("entity").path("id").asString());
            }
            default -> type = "ignored." + (event.isBlank() ? "unknown" : event);
        }
        return mapper.writeValueAsBytes(Map.of("id", "rzp:" + eventId, "type", type, "data", data));
    }

    private static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
