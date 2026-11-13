package in.railops.reservation.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Events produced by reservation-service. Will move to a published reservation-contracts artifact
 * once a second service consumes them; kept here until then to avoid a premature shared jar.
 * Rules: additive changes only; never repurpose a field.
 */
public final class ReservationEvents {

    public static final String TOPIC_BOOKING = "reservation.booking.v1";
    public static final String TOPIC_INVENTORY = "reservation.inventory.v1";

    private ReservationEvents() {
    }

    public record BookingInitiated(String pnr, long trainRunId, LocalDate runDate, String classCode, String quotaCode,
                                   int fromSeq, int toSeq, BigDecimal totalFare, int passengerCount,
                                   Instant holdExpiresAt) {
    }

    public record BookingConfirmed(String pnr, long trainRunId, UUID paymentId, boolean reallocated) {
    }

    public record BookingExpired(String pnr, long trainRunId) {
    }

    /** Asks payment-service to refund. {@code idempotencyKey} makes the refund at-most-once across retries. */
    public record RefundRequested(String pnr, UUID paymentId, BigDecimal amount, String reason, String idempotencyKey) {
    }

    // Typed payloads for every event (Phase 11): Map.of(...) payloads were invisible to the compiler and to the
    // contract tests, and a renamed key would only surface as a blank field in a customer SMS.
    public record BookingCancelled(String pnr, UUID cancellationId, java.util.List<Integer> passengers,
                                   BigDecimal refundAmount, String bookingStatus) {
    }

    public record WaitlistChartCancelled(String pnr, java.util.List<Integer> passengers, BigDecimal refundAmount,
                                         String bookingStatus) {
    }

    public record BookingRunCancelled(String pnr, long trainRunId, BigDecimal refundAmount, String reason) {
    }

    public record PassengerPromoted(String pnr, long passengerId, String newStatus, String statusText) {
    }

    /** Drives the search read model and WL promotion. Reasons: BOOKED, HOLD_EXPIRED, CANCELLED, PROMOTION. */
    public record InventoryChanged(long trainRunId, String classCode, String quotaCode, int fromSeq, int toSeq,
                                   String reason) {

        public boolean freesCapacity() {
            return "HOLD_EXPIRED".equals(reason) || "CANCELLED".equals(reason);
        }
    }
}
