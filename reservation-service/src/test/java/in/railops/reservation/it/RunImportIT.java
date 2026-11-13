package in.railops.reservation.it;

import in.railops.reservation.application.BookingCommand;
import in.railops.reservation.application.BookingService;
import in.railops.reservation.application.BookingView;
import in.railops.reservation.application.schedule.RunImportService;
import in.railops.reservation.application.schedule.RunImportService.ImportCommand;
import in.railops.reservation.application.schedule.RunImportService.Outcome;
import in.railops.reservation.application.schedule.TimetablePort;
import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.error.ErrorCode;
import in.railops.reservation.domain.model.PassengerSpec;
import in.railops.reservation.domain.model.Quota;
import in.railops.reservation.domain.model.TravelClass;
import in.railops.reservation.domain.policy.RunPlanner;
import in.railops.reservation.domain.policy.RunPlanner.CoachSpec;
import in.railops.reservation.domain.policy.RunPlanner.Timetable;
import in.railops.reservation.domain.policy.RunPlanner.TimetableStop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Timetable import on real PostgreSQL: rows written, idempotent re-import, and an imported run takes bookings. */
class RunImportIT extends AbstractReservationIT {

    @MockitoBean
    TimetablePort timetables;

    @Autowired RunImportService imports;
    @Autowired BookingService bookings;

    final LocalDate first = LocalDate.now(RunPlanner.IST).plusDays(5);

    static TimetableStop stop(String code, String arr, String dep, int km) {
        return new TimetableStop(code, arr == null ? null : LocalTime.parse(arr), dep == null ? null : LocalTime.parse(dep), km);
    }

    @BeforeEach
    void raillensHasTrain12951() {
        when(timetables.fetch(anyString())).thenReturn(Optional.empty());
        when(timetables.fetch("12951")).thenReturn(Optional.of(new Timetable("12951", "Mumbai Rajdhani", List.of(
                stop("MMCT", null, "17:00", 0), stop("BRC", "21:00", "21:05", 392), stop("RTM", "00:30", "00:35", 653),
                stop("KOTA", "04:20", "04:30", 918), stop("NDLS", "08:30", null, 1384)))));
    }

    ImportCommand command(LocalDate from, LocalDate to) {
        return new ImportCommand("12951", from, to,
                List.of(new CoachSpec(TravelClass.THIRD_AC, 2), new CoachSpec(TravelClass.SLEEPER, 1)), null, null);
    }

    @Test
    void creates_one_complete_run_per_date_and_reimporting_changes_nothing() {
        var result = imports.importRuns(command(first, first.plusDays(2)), "admin-1");
        assertThat(result.trainName()).isEqualTo("Mumbai Rajdhani");
        assertThat(result.dates()).extracting(d -> d.outcome()).containsOnly(Outcome.CREATED).hasSize(3);

        long run = result.dates().getFirst().trainRunId();
        assertThat(jdbc.sql("SELECT count(*) FROM reservation.run_stop WHERE train_run_id = :r").param("r", run)
                .query(Integer.class).single()).isEqualTo(5);
        assertThat(jdbc.sql("SELECT string_agg(coach_label, ',' ORDER BY position) FROM reservation.run_coach WHERE train_run_id = :r")
                .param("r", run).query(String.class).single()).isEqualTo("B1,B2,S1");
        // 4 segments x (3A GN, 3A TQ, SL GN, SL TQ)
        assertThat(jdbc.sql("SELECT count(*) FROM reservation.segment_inventory WHERE train_run_id = :r").param("r", run)
                .query(Integer.class).single()).isEqualTo(16);
        assertThat(jdbc.sql("SELECT cnf_capacity FROM reservation.segment_inventory WHERE train_run_id = :r AND class_code='3A' AND quota_code='GN' AND seg_idx=3")
                .param("r", run).query(Integer.class).single()).isEqualTo(114);
        assertThat(jdbc.sql("SELECT count(*) FROM reservation.outbox WHERE event_type = 'StaffAction'")
                .query(Integer.class).single()).as("each created run audited").isEqualTo(3);

        var again = imports.importRuns(command(first, first.plusDays(3)), "admin-1");
        assertThat(again.dates()).extracting(d -> d.outcome())
                .containsExactly(Outcome.ALREADY_EXISTS, Outcome.ALREADY_EXISTS, Outcome.ALREADY_EXISTS, Outcome.CREATED);
        assertThat(again.dates().getFirst().trainRunId()).isEqualTo(run);
    }

    @Test
    void an_imported_run_takes_a_booking() {
        long run = imports.importRuns(command(first, first), "admin-1").dates().getFirst().trainRunId();
        BookingView v = bookings.create(UUID.randomUUID(), UUID.randomUUID().toString(), new BookingCommand(run, 1, 3,
                TravelClass.THIRD_AC, Quota.GN, List.of(new PassengerSpec(1, "Asha Rao", 40, 'F', null, true)), false,
                "9820012345", null));
        assertThat(v.trainRunId()).isEqualTo(run);
        assertThat(v.passengers().getFirst().currentStatus()).startsWith("CNF");
        assertInventoryInvariants();
    }

    @Test
    void past_dates_are_skipped_and_the_rest_created() {
        var result = imports.importRuns(command(LocalDate.now(RunPlanner.IST).minusDays(1), first), "admin-1");
        assertThat(result.dates().getFirst().outcome()).isEqualTo(Outcome.SKIPPED);
        assertThat(result.dates().getFirst().detail()).contains("past");
        assertThat(result.dates().getLast().outcome()).isEqualTo(Outcome.CREATED);
    }

    @Test
    void unknown_trains_and_bad_requests_are_refused_without_writing_anything() {
        assertThatThrownBy(() -> imports.importRuns(new ImportCommand("99999", first, first,
                List.of(new CoachSpec(TravelClass.SLEEPER, 1)), null, null), "admin-1"))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.RES_TIMETABLE_NOT_FOUND));
        assertThatThrownBy(() -> imports.importRuns(new ImportCommand("12951", first, first,
                List.of(new CoachSpec(TravelClass.SLEEPER, 20), new CoachSpec(TravelClass.THIRD_AC, 5)), null, null), "admin-1"))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.RES_TIMETABLE_INVALID));
        assertThatThrownBy(() -> imports.importRuns(command(first, first.plusDays(70)), "admin-1"))
                .hasMessageContaining("at most 62 dates");
        assertThat(jdbc.sql("SELECT count(*) FROM reservation.train_run WHERE train_no = '12951'").query(Integer.class).single()).isZero();
    }
}
