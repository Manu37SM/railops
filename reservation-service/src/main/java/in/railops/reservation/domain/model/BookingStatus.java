package in.railops.reservation.domain.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum BookingStatus {
    PENDING_PAYMENT, CONFIRMED, PARTIALLY_CANCELLED, CANCELLED,
    EXPIRED, FAILED_REFUNDING, CHART_CANCELLED, RUN_CANCELLED;

    private static final Map<BookingStatus, Set<BookingStatus>> ALLOWED = Map.of(
            PENDING_PAYMENT, EnumSet.of(CONFIRMED, EXPIRED, FAILED_REFUNDING, CANCELLED),
            EXPIRED, EnumSet.of(CONFIRMED, FAILED_REFUNDING),
            CONFIRMED, EnumSet.of(PARTIALLY_CANCELLED, CANCELLED, CHART_CANCELLED, RUN_CANCELLED),
            PARTIALLY_CANCELLED, EnumSet.of(PARTIALLY_CANCELLED, CANCELLED, CHART_CANCELLED, RUN_CANCELLED));

    public boolean canMoveTo(BookingStatus next) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(next);
    }

    public BookingStatus requireTransition(BookingStatus next) {
        if (!canMoveTo(next)) {
            throw new IllegalStateException("illegal booking transition " + this + " -> " + next);
        }
        return next;
    }
}
