package in.railops.reservation.adapter.in.scheduler;

import in.railops.reservation.adapter.out.http.PaymentClient.PaymentStatus;
import in.railops.reservation.adapter.out.persistence.BookingRepository.BookingKey;
import in.railops.reservation.adapter.out.persistence.BookingRepository.ExpiredHold;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** MONEY: the rule that decides whether a paid customer keeps their berth when the event path is behind (INC-001). */
class HoldSweeperDecisionTest {

    static final Instant EXPIRED_AT = Instant.parse("2026-11-12T04:30:00Z");
    final HoldSweeper sweeper = new HoldSweeper(null, null, null, null, null, new SimpleMeterRegistry(),
            Duration.ofMinutes(2), Duration.ofMinutes(15));
    final ExpiredHold hold = new ExpiredHold(new BookingKey(LocalDate.of(2026, 11, 20), 1), "2400000018", UUID.randomUUID(), EXPIRED_AT);

    @ParameterizedTest(name = "{0} at +{1}s (status known={2}) -> {3}")
    @CsvSource({
            "CAPTURED, 5,    true,  CONFIRM",   // paid: keep the berth no matter how late the event is
            "CAPTURED, 3600, true,  CONFIRM",
            "PENDING,  60,   true,  WAIT",      // mid-checkout (3-D Secure): short grace
            "CREATED,  119,  true,  WAIT",
            "PENDING,  121,  true,  EXPIRE",    // grace over: a later capture takes the late-payment path
            "FAILED,   1,    true,  EXPIRE",
            "EXPIRED,  1,    true,  EXPIRE",
            "UNKNOWN_ID, 1,  true,  EXPIRE",    // payment-service has never heard of it
            "-,        60,   false, WAIT",      // payment-service unreachable: don't expire blind ...
            "-,        899,  false, WAIT",
            "-,        901,  false, EXPIRE"})   // ... but not forever: a hold can't block a berth indefinitely
    void decides(String status, long secondsAfterExpiry, boolean known, HoldSweeper.Decision expected) {
        PaymentStatus s = !known || status.equals("UNKNOWN_ID") ? null
                : new PaymentStatus(hold.paymentId(), hold.pnr(), status, new BigDecimal("2915.00"), "INR");
        assertThat(sweeper.decide(hold, s, known, EXPIRED_AT.plusSeconds(secondsAfterExpiry))).isEqualTo(expected);
    }
}
