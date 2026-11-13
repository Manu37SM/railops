package in.railops.reservation.adapter.out.http;

import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.error.ErrorCode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * payment-service internal API. Retrying a POST is safe only because the request carries an
 * Idempotency-Key (the PNR): payment-service returns the existing live payment instead of creating a
 * second one. The amount sent is the server-side fare, never anything from the browser.
 */
@Component
public class PaymentClient {

    public record CreatePayment(String pnr, UUID userId, BigDecimal amount, String currency, Instant payBy) {
    }

    public record PaymentStatus(UUID paymentId, String pnr, String status, BigDecimal capturedAmount, String currency) {
    }

    /** One call for many payments; throws on any failure (callers treat that as "unknown", never as "unpaid"). */
    public java.util.Map<UUID, PaymentStatus> statuses(java.util.Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return java.util.Map.of();
        }
        PaymentStatus[] got = client.get()
                .uri(u -> u.path("/internal/v1/payments/status").queryParam("ids", ids.toArray()).build())
                .header("Authorization", "Bearer " + tokens.token())
                .accept(org.springframework.http.MediaType.APPLICATION_JSON)
                .retrieve()
                .body(PaymentStatus[].class);
        java.util.Map<UUID, PaymentStatus> out = new java.util.HashMap<>();
        for (PaymentStatus s : got == null ? new PaymentStatus[0] : got) {
            out.put(s.paymentId(), s);
        }
        return out;
    }

    public record PaymentIntent(UUID paymentId, String checkoutUrl, BigDecimal amount, String status) {
    }

    private final RestClient client;
    private final ServiceTokenClient tokens;

    public PaymentClient(RestClient.Builder builder, ServiceTokenClient tokens,
                         @Value("${railops.payment.base-url}") String baseUrl) {
        var rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofMillis(300));
        rf.setReadTimeout(Duration.ofSeconds(2));
        this.client = builder.baseUrl(baseUrl).requestFactory(rf).build();
        this.tokens = tokens;
    }

    @Retry(name = "payment")
    @CircuitBreaker(name = "payment", fallbackMethod = "unavailable")
    public PaymentIntent create(CreatePayment req) {
        try {
            return post(req);
        } catch (HttpClientErrorException.Unauthorized e) {
            tokens.invalidate(); // stale token (e.g. key rotation): refresh once, then let Retry handle the rest
            return post(req);
        }
    }

    private PaymentIntent post(CreatePayment req) {
        PaymentIntent intent = client.post().uri("/internal/v1/payments")
                .header("Authorization", "Bearer " + tokens.token())
                .header("Idempotency-Key", "pnr-" + req.pnr())
                // Explicit: jackson-dataformat-yaml is on the classpath (springdoc), so without this the wire
                // format depends on converter ORDER. Found by PaymentHttpConsumerContractTest (Phase 11).
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .accept(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(PaymentIntent.class);
        if (intent == null || intent.paymentId() == null || intent.amount() == null
                || intent.amount().compareTo(req.amount()) != 0) {
            // A payment for a different amount than the fare must never be shown to the user.
            throw new IllegalStateException("payment-service returned an inconsistent intent for " + req.pnr());
        }
        return intent;
    }

    /**
     * Phase 5, simulated payments only: plays the passenger at the simulated checkout. No retry annotation: the
     * caller shows the error and the passenger taps Pay again (payment-service ignores a second "pay").
     */
    public void simulateCheckout(UUID paymentId, boolean success) {
        try {
            client.post().uri(u -> u.path("/internal/v1/sim/payments/{id}/pay").queryParam("outcome", success ? "success" : "failure")
                            .build(paymentId))
                    .header("Authorization", "Bearer " + tokens.token())
                    .retrieve().toBodilessEntity();
        } catch (org.springframework.web.client.RestClientException e) {
            throw new DomainException(ErrorCode.PAYMENT_UNAVAILABLE, "payment service is unavailable, please retry");
        }
    }

    @SuppressWarnings("unused")
    private PaymentIntent unavailable(CreatePayment req, Throwable cause) {
        throw new DomainException(ErrorCode.PAYMENT_UNAVAILABLE, "payment service is unavailable, please retry");
    }
}
