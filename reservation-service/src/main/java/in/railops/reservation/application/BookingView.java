package in.railops.reservation.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** What the API returns for a booking; also the stored idempotent response. Contains no contact PII. */
public record BookingView(String pnr, String status, long trainRunId, LocalDate runDate, String classCode,
                          String quotaCode, int fromSeq, int toSeq, BigDecimal totalFare, Instant holdExpiresAt,
                          List<PassengerView> passengers) {

    public record PassengerView(int seqNo, String name, int age, char gender, String bookingStatus,
                                String currentStatus) {
    }
}
