package in.railops.reservation.contracts;

import java.math.BigDecimal;
import java.util.UUID;

/** Local view of payment-service events (tolerant reader: only the fields reservation needs). */
public final class PaymentEvents {

    public static final String TOPIC = "payment.events.v1";
    public static final String CAPTURED = "PaymentCaptured";
    public static final String FAILED = "PaymentFailed";

    private PaymentEvents() {
    }

    public record PaymentCaptured(UUID paymentId, String pnr, BigDecimal amount, String currency) {
    }

    public record PaymentFailed(UUID paymentId, String pnr, String reason) {
    }
}
