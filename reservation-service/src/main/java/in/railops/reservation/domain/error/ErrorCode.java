package in.railops.reservation.domain.error;

/** Stable, client-visible error codes. Never rename: clients switch on these strings. */
public enum ErrorCode {
    RES_RUN_NOT_FOUND,
    RES_RUN_NOT_BOOKABLE,
    RES_INVALID_JOURNEY,
    RES_QUOTA_INELIGIBLE,
    RES_REGRET,
    RES_ONLY_IF_CNF_UNMET,
    RES_HOLD_EXPIRED,
    RES_BOOKING_NOT_FOUND,
    RES_INVENTORY_INCONSISTENT,
    RES_CANCELLATION_CLOSED,
    RES_INVALID_CANCELLATION,
    RES_BOOKING_NOT_PAYABLE,
    RES_CHART_NOT_ALLOWED,
    PAYMENT_UNAVAILABLE,
    RES_WAITING_ROOM,
    IDEM_KEY_REUSED,
    IDEM_IN_PROGRESS,
    DEPENDENCY_UNAVAILABLE,
    RES_TIMETABLE_NOT_FOUND,
    RES_TIMETABLE_INVALID,
    /** Phase 5: the simulated checkout is off (a real payment gateway is in use). */
    RES_SIMULATED_PAYMENTS_OFF
}
