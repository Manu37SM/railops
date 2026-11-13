package in.railops.payment.contracts;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Payloads published on {@code payment.v1}. Schemas: contracts/events/*.v1.schema.json (verified by
 * PaymentEventContractTest). MONEY: amounts are rupees with paise, serialised as JSON numbers from BigDecimal.
 */
public final class PaymentEvents {

    private PaymentEvents() {
    }

    public record PaymentCaptured(UUID paymentId, String pnr, BigDecimal amount, String currency) {
    }

    public record PaymentFailed(UUID paymentId, String pnr, String reason) {
    }

    public record RefundSucceeded(UUID refundId, UUID paymentId, String pnr, BigDecimal amount) {
    }

    public record RefundNeedsReview(UUID refundId, UUID paymentId, BigDecimal amount) {
    }
}
