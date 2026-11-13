package in.railops.reservation.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

import static in.railops.reservation.application.WaitingRoomService.LOTTERY_SPAN;
import static in.railops.reservation.application.WaitingRoomService.score;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Tatkal fairness rule, as arithmetic. Redis stores scores as doubles, so exactness is part of the rule. */
class WaitingRoomScoreTest {

    static final long OPENS = Instant.parse("2026-11-19T04:30:00Z").toEpochMilli();   // 10:00 IST

    @Test
    void the_luckiest_early_joiner_is_still_before_someone_joining_at_the_opening_millisecond() {
        long bestEarlyWorstCase = score(OPENS - 1, OPENS, LOTTERY_SPAN - 1);
        long firstLate = score(OPENS, OPENS, 0);
        assertThat(bestEarlyWorstCase).isLessThan(firstLate);
        // ... and still so after the round trip through a Redis double.
        assertThat((double) bestEarlyWorstCase).isLessThan((double) firstLate);
        assertThat((long) (double) bestEarlyWorstCase).isEqualTo(bestEarlyWorstCase);
    }

    @Test
    void early_place_does_not_depend_on_how_early_you_joined() {
        long draw = 123_456_789;
        assertThat(score(OPENS - 3_600_000, OPENS, draw)).isEqualTo(score(OPENS - 50, OPENS, draw));
    }

    @Test
    void late_joiners_are_first_come_first_served_to_the_millisecond() {
        assertThat(score(OPENS + 1, OPENS, 999)).isLessThan(score(OPENS + 2, OPENS, 0));
    }

    @Test
    void scores_stay_exact_doubles_for_decades() {
        long farFuture = Instant.parse("2100-01-01T00:00:00Z").toEpochMilli();
        for (int i = 0; i < 10_000; i++) {
            long s = score(farFuture - 1, farFuture, ThreadLocalRandom.current().nextLong(LOTTERY_SPAN));
            assertThat(Math.abs(s)).isLessThan(1L << 53);
            assertThat((long) (double) s).isEqualTo(s);
        }
    }

    @Test
    void out_of_range_draw_is_a_bug_not_a_score() {
        assertThatThrownBy(() -> score(0, 1, LOTTERY_SPAN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> score(0, 1, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
