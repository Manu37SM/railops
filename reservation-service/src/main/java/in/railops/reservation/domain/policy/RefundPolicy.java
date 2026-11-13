package in.railops.reservation.domain.policy;

import in.railops.reservation.domain.model.PassengerStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Map;

/**
 * Per-passenger cancellation charge and refund. Pure function; all amounts INR, scale 2, HALF_UP.
 *
 * <p>The thresholds and amounts are CONFIGURATION with illustrative defaults, not a statement of current
 * railway tariff. Time windows are measured to the scheduled departure from the passenger's BOARDING
 * station, not the train's origin.
 *
 * <pre>
 * NOSB child                → nothing paid, nothing refunded
 * WL / RAC                  → clerkage only
 * CNF + Tatkal              → no refund
 * CNF, > 48 h               → flat charge per class
 * CNF, 12–48 h              → max(25 % of fare, flat)
 * CNF, 4–12 h               → max(50 % of fare, flat)
 * CNF, < 4 h                → no refund
 * </pre>
 * The charge never exceeds what was paid, so a refund is never negative.
 */
public final class RefundPolicy {

    public record Config(Map<String, BigDecimal> flatChargeByClass, BigDecimal clerkage) {
    }

    public record PassengerRefund(int seqNo, BigDecimal paid, BigDecimal charge, BigDecimal refund, String rule) {
    }

    private static final BigDecimal QUARTER = new BigDecimal("0.25");
    private static final BigDecimal HALF = new BigDecimal("0.50");

    private final Config config;

    public RefundPolicy(Config config) {
        this.config = config;
    }

    public PassengerRefund compute(int seqNo, PassengerStatus status, BigDecimal paid, String classCode,
                                   boolean tatkal, Duration toDeparture) {
        BigDecimal p = paid.setScale(2, RoundingMode.HALF_UP);
        if (status == PassengerStatus.NOSB || p.signum() == 0) {
            return result(seqNo, p, p, "NO_FARE_PAID");
        }
        if (status == PassengerStatus.WL || status == PassengerStatus.RAC) {
            return result(seqNo, p, config.clerkage(), "WL_RAC_CLERKAGE");
        }
        if (status != PassengerStatus.CNF) {
            throw new IllegalArgumentException("passenger " + seqNo + " is not cancellable in status " + status);
        }
        if (tatkal) {
            return result(seqNo, p, p, "TATKAL_CONFIRMED_NO_REFUND");
        }
        BigDecimal flat = config.flatChargeByClass().getOrDefault(classCode, config.clerkage());
        long minutes = toDeparture.toMinutes();
        if (minutes > 48 * 60) {
            return result(seqNo, p, flat, "MORE_THAN_48H_FLAT");
        }
        if (minutes > 12 * 60) {
            return result(seqNo, p, flat.max(p.multiply(QUARTER)), "12_TO_48H_25PCT");
        }
        if (minutes > 4 * 60) {
            return result(seqNo, p, flat.max(p.multiply(HALF)), "4_TO_12H_50PCT");
        }
        return result(seqNo, p, p, "LESS_THAN_4H_NO_REFUND");
    }

    private static PassengerRefund result(int seqNo, BigDecimal paid, BigDecimal charge, String rule) {
        BigDecimal c = charge.min(paid).setScale(2, RoundingMode.HALF_UP);
        return new PassengerRefund(seqNo, paid, c, paid.subtract(c), rule);
    }
}
