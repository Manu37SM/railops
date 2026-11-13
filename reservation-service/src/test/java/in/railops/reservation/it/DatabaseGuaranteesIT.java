package in.railops.reservation.it;

import in.railops.reservation.adapter.in.scheduler.PartitionMaintenance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guarantees the DATABASE enforces on its own, tested without application code in the way: if the Java side ever
 * has a bug, these are what stop a double-booked berth from being committed.
 */
class DatabaseGuaranteesIT extends AbstractReservationIT {

    @Autowired PartitionMaintenance partitions;

    int insert(LocalDate day, long coach, int berth, String seg, String slot) {
        return jdbc.sql("""
                        INSERT INTO reservation.allocation (run_date, train_run_id, run_coach_id, berth_no, seg_range,
                               slot_range, class_code, quota_code, state, passenger_id)
                        VALUES (:d, 1, :c, :b, CAST(:seg AS int4range), CAST(:slot AS int4range), '3A', 'GN', 'CONFIRMED', 1)""")
                .param("d", day).param("c", coach).param("b", berth).param("seg", seg).param("slot", slot).update();
    }

    static final LocalDate RUN = LocalDate.of(2026, 11, 20);

    @Test
    void overlapping_journeys_on_one_berth_are_rejected_by_the_database() {
        insert(RUN, 1, 7, "[0,2)", "[1,3)");
        insert(RUN, 1, 7, "[2,4)", "[1,3)");                 // MMCT-RTM then RTM-NDLS: same berth, fine
        assertThatThrownBy(() -> insert(RUN, 1, 7, "[1,3)", "[1,3)"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ex_no_double_berth");
    }

    @Test
    void two_rac_halves_share_a_berth_but_a_full_booking_cannot_join_them() {
        insert(RUN, 1, 7, "[0,4)", "[1,2)");
        insert(RUN, 1, 7, "[0,4)", "[2,3)");
        assertThatThrownBy(() -> insert(RUN, 1, 7, "[0,4)", "[1,3)")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(RUN, 1, 7, "[3,4)", "[2,3)")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void malformed_ranges_are_rejected() {
        assertThatThrownBy(() -> insert(RUN, 1, 8, "[2,2)", "[1,3)")).isInstanceOf(DataIntegrityViolationException.class);  // empty
        assertThatThrownBy(() -> insert(RUN, 1, 8, "[0,4)", "[1,4)")).isInstanceOf(DataIntegrityViolationException.class);  // bad slot
    }

    @Test
    void maintenance_keeps_partitions_ahead_and_every_new_partition_has_the_exclusion_constraint() {
        assertThat(partitions.ensure()).isTrue();
        assertThat(partitions.runwayDays()).as("runway after maintenance").isGreaterThan(150);

        LocalDate farRun = YearMonth.now().plusMonths(6).atDay(10);
        String suffix = farRun.format(DateTimeFormatter.ofPattern("yyyyMM"));
        assertThat(jdbc.sql("SELECT count(*) FROM pg_constraint WHERE conname = :n").param("n", "ex_no_double_berth_" + suffix)
                .query(Integer.class).single()).as("constraint exists on the newest partition").isEqualTo(1);

        insert(farRun, 1, 7, "[0,4)", "[1,3)");
        assertThatThrownBy(() -> insert(farRun, 1, 7, "[1,2)", "[1,3)"))
                .as("the guarantee holds in a partition created by maintenance, not only by the migration")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(partitions.ensure()).as("idempotent").isTrue();
    }

    @Test
    void without_maintenance_a_booking_beyond_the_window_would_fail() {
        // Documents the failure mode the job prevents (Phase 11 finding).
        LocalDate beyond = YearMonth.now().plusMonths(24).atDay(1);
        assertThatThrownBy(() -> insert(beyond, 1, 7, "[0,4)", "[1,3)"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("no partition");
    }
}
