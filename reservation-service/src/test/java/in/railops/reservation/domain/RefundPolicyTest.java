package in.railops.reservation.domain;

import in.railops.reservation.domain.model.PassengerStatus;
import in.railops.reservation.domain.policy.RefundPolicy;
import in.railops.reservation.domain.policy.RefundPolicy.PassengerRefund;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RefundPolicyTest {

    private final RefundPolicy policy = new RefundPolicy(new RefundPolicy.Config(
            Map.of("3A", new BigDecimal("180.00"), "SL", new BigDecimal("120.00")), new BigDecimal("60.00")));

    /** Boundaries are where refund bugs live: exactly 48 h is NOT "more than 48 h". */
    @ParameterizedTest(name = "{0} min before departure -> charge {1} ({2})")
    @CsvSource({
            "4000, 180.00, MORE_THAN_48H_FLAT",
            "2881, 180.00, MORE_THAN_48H_FLAT",
            "2880, 500.00, 12_TO_48H_25PCT",     // 48 h exactly: 25 % of 2000
            "721,  500.00, 12_TO_48H_25PCT",
            "720,  1000.00, 4_TO_12H_50PCT",     // 12 h exactly
            "241,  1000.00, 4_TO_12H_50PCT",
            "240,  2000.00, LESS_THAN_4H_NO_REFUND",
            "0,    2000.00, LESS_THAN_4H_NO_REFUND"})
    void confirmedWindows(long minutes, String expectedCharge, String rule) {
        PassengerRefund r = policy.compute(1, PassengerStatus.CNF, new BigDecimal("2000.00"), "3A", false,
                Duration.ofMinutes(minutes));
        assertThat(r.charge()).isEqualByComparingTo(expectedCharge);
        assertThat(r.rule()).isEqualTo(rule);
        assertThat(r.refund()).isEqualByComparingTo(new BigDecimal("2000.00").subtract(new BigDecimal(expectedCharge)));
    }

    @Test
    void flatChargeWinsOverPercentageOnCheapTickets() {
        PassengerRefund r = policy.compute(1, PassengerStatus.CNF, new BigDecimal("300.00"), "3A", false, Duration.ofHours(20));
        assertThat(r.charge()).isEqualByComparingTo("180.00"); // 25 % = 75 < flat 180
    }

    @Test
    void chargeNeverExceedsFareSoRefundIsNeverNegative() {
        PassengerRefund r = policy.compute(1, PassengerStatus.CNF, new BigDecimal("100.00"), "3A", false, Duration.ofDays(10));
        assertThat(r.charge()).isEqualByComparingTo("100.00");
        assertThat(r.refund()).isEqualByComparingTo("0.00");
    }

    @Test
    void waitlistTatkalAndChildRules() {
        assertThat(policy.compute(1, PassengerStatus.WL, new BigDecimal("2000"), "3A", true, Duration.ofHours(1)).refund())
                .isEqualByComparingTo("1940.00"); // WL gets clerkage-only even for Tatkal, even late
        assertThat(policy.compute(1, PassengerStatus.CNF, new BigDecimal("2000"), "3A", true, Duration.ofDays(5)).refund())
                .isEqualByComparingTo("0.00");
        assertThat(policy.compute(1, PassengerStatus.NOSB, BigDecimal.ZERO, "3A", false, Duration.ofDays(5)).refund())
                .isEqualByComparingTo("0.00");
    }
}
