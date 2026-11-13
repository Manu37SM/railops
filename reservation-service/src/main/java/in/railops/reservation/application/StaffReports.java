package in.railops.reservation.application;

import java.math.BigDecimal;
import java.util.List;

/** Response shapes of the staff run reports. Simple names are the OpenAPI schema names: do not rename. */
public final class StaffReports {

    private StaffReports() {
    }

    public record PoolOccupancy(String classCode, String quotaCode, int cnfCapacity, int cnfPeakUsed,
                                int racCapacity, int racPeakUsed, int wlIssued) {
    }

    public record PoolFunnel(String classCode, String quotaCode, int cnfCapacity, int cnfPeakUsed,
                             double occupancy, int wlIssued, int wlPromoted, int wlChartExpired, int wlCancelled,
                             int wlOpen) {
    }

    public record MoneySummary(long paidBookings, BigDecimal grossBookedFare, long userCancellations,
                               BigDecimal refunds) {
    }

    /**
     * Money figures are INDICATIVE, from reservation's own rows: gross = fares of bookings that were paid (any
     * post-payment status), refunds = sum of cancellation refunds computed here. The payment ledger is the system of
     * record; reconcile against it before any finance use.
     */
    public record RunAnalytics(long trainRunId, List<PoolFunnel> pools, long paidBookings, long userCancellations,
                               BigDecimal grossBookedFare, BigDecimal refunds, BigDecimal netIndicative,
                               long predictionsLogged, long predictionsResolved, Double predictionBrier) {
    }
}
