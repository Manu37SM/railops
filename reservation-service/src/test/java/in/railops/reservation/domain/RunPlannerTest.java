package in.railops.reservation.domain;

import in.railops.reservation.domain.model.Quota;
import in.railops.reservation.domain.model.TravelClass;
import in.railops.reservation.domain.policy.RunPlanner;
import in.railops.reservation.domain.policy.RunPlanner.CoachSpec;
import in.railops.reservation.domain.policy.RunPlanner.DateNotPlannable;
import in.railops.reservation.domain.policy.RunPlanner.Layout;
import in.railops.reservation.domain.policy.RunPlanner.Options;
import in.railops.reservation.domain.policy.RunPlanner.PlannedPool;
import in.railops.reservation.domain.policy.RunPlanner.PlanningException;
import in.railops.reservation.domain.policy.RunPlanner.RunPlan;
import in.railops.reservation.domain.policy.RunPlanner.Timetable;
import in.railops.reservation.domain.policy.RunPlanner.TimetableStop;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunPlannerTest {

    static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");
    static final LocalDate DATE = LocalDate.of(2026, 12, 1);
    static final Map<String, Layout> LAYOUTS = Map.of(
            "LHB_3A", new Layout(72, 9), "LHB_SL", new Layout(72, 9), "LHB_2A", new Layout(48, 8));

    static TimetableStop stop(String code, String arr, String dep, int km) {
        return new TimetableStop(code, arr == null ? null : LocalTime.parse(arr), dep == null ? null : LocalTime.parse(dep), km);
    }

    /** An overnight Mumbai - Delhi run that crosses midnight between Vadodara and Ratlam. */
    static Timetable overnight() {
        return new Timetable("12951", "Rajdhani", List.of(
                stop("MMCT", null, "17:00", 0),
                stop("BRC", "21:00", "21:05", 392),
                stop("RTM", "00:30", "00:35", 653),
                stop("KOTA", "04:20", "04:30", 918),
                stop("NDLS", "08:30", null, 1384)));
    }

    static RunPlan plan(Timetable tt, List<CoachSpec> consist) {
        return RunPlanner.plan(tt, DATE, consist, LAYOUTS, Options.DEFAULTS, NOW);
    }

    static OffsetDateTime ist(String local) {
        return LocalDateTimeIst.of(local);
    }

    static final class LocalDateTimeIst {
        static OffsetDateTime of(String local) {
            return java.time.LocalDateTime.parse(local).atZone(RunPlanner.IST).toOffsetDateTime();
        }
    }

    @Test
    void clock_times_become_real_timestamps_across_midnight() {
        RunPlan p = plan(overnight(), List.of(new CoachSpec(TravelClass.THIRD_AC, 2)));
        assertThat(p.stops()).hasSize(5);
        assertThat(p.stops().get(0).arrival()).isNull();
        assertThat(p.stops().get(0).departure()).isEqualTo(ist("2026-12-01T17:00"));
        assertThat(p.stops().get(1).arrival()).isEqualTo(ist("2026-12-01T21:00"));
        assertThat(p.stops().get(2).arrival()).as("after midnight").isEqualTo(ist("2026-12-02T00:30"));
        assertThat(p.stops().get(4).arrival()).isEqualTo(ist("2026-12-02T08:30"));
        assertThat(p.stops().get(4).departure()).isNull();
        assertThat(p.stops().get(4).arrival().getOffset().getTotalSeconds()).as("IST, +05:30").isEqualTo(19800);
        assertThat(p.segments()).isEqualTo(4);
    }

    @Test
    void a_halt_spanning_midnight_departs_the_next_day() {
        Timetable tt = new Timetable("12345", "x", List.of(stop("AAA", null, "20:00", 0),
                stop("BBB", "23:55", "00:10", 300), stop("CCC", "04:00", null, 600)));
        RunPlan p = RunPlanner.plan(tt, DATE, List.of(new CoachSpec(TravelClass.SLEEPER, 1)), LAYOUTS, Options.DEFAULTS, NOW);
        assertThat(p.stops().get(1).arrival()).isEqualTo(ist("2026-12-01T23:55"));
        assertThat(p.stops().get(1).departure()).isEqualTo(ist("2026-12-02T00:10"));
    }

    @Test
    void booking_window_follows_indian_railways_rules() {
        RunPlan p = plan(overnight(), List.of(new CoachSpec(TravelClass.THIRD_AC, 1)));
        assertThat(p.bookingOpensAt()).isEqualTo(ist("2026-10-02T17:00"));   // 60 days before departure
        assertThat(p.tatkalOpensAt()).isEqualTo(ist("2026-11-30T10:00"));    // 10:00 the day before
        assertThat(p.chart1DueAt()).isEqualTo(ist("2026-12-01T13:00"));      // 4 h before
        assertThat(p.chart2DueAt()).isEqualTo(ist("2026-12-01T16:30"));      // 30 min before
    }

    @Test
    void seats_come_from_the_coach_layouts_and_never_exceed_the_berths() {
        RunPlan p = plan(overnight(), List.of(new CoachSpec(TravelClass.THIRD_AC, 2), new CoachSpec(TravelClass.SECOND_AC, 1)));
        assertThat(p.coaches()).extracting(c -> c.label() + "/" + c.coachType() + "/" + c.position())
                .containsExactly("B1/LHB_3A/1", "B2/LHB_3A/2", "A1/LHB_2A/3");
        // 3A: 144 berths, 18 side-lowers -> 126 confirmed (12 Tatkal, 114 general), 36 RAC places.
        assertThat(p.pools()).contains(
                new PlannedPool("3A", Quota.GN, 114, 36, 57),
                new PlannedPool("3A", Quota.TQ, 12, 0, 6),
                new PlannedPool("2A", Quota.GN, 36, 16, 18),
                new PlannedPool("2A", Quota.TQ, 4, 0, 2));
        for (String cls : List.of("3A", "2A")) {
            int berths = cls.equals("3A") ? 144 : 48;
            int confirmed = p.pools().stream().filter(x -> x.classCode().equals(cls)).mapToInt(PlannedPool::cnfCapacity).sum();
            int racPlaces = p.pools().stream().filter(x -> x.classCode().equals(cls)).mapToInt(PlannedPool::racCapacity).sum();
            assertThat(confirmed + racPlaces / 2).as(cls + ": confirmed seats + RAC berths = physical berths").isEqualTo(berths);
        }
    }

    @Test
    void zero_tatkal_means_no_tatkal_pool() {
        RunPlan p = RunPlanner.plan(overnight(), DATE, List.of(new CoachSpec(TravelClass.SLEEPER, 1)), LAYOUTS,
                new Options(0, 50, 60, 120), NOW);
        assertThat(p.pools()).extracting(PlannedPool::quota).containsExactly(Quota.GN);
    }

    @Test
    void broken_timetables_are_refused_with_every_problem_listed() {
        Timetable bad = new Timetable("12951", "x", List.of(
                stop("MMCT", null, null, 0),
                stop("BRC", null, "21:05", 392),
                stop("BRC", "23:00", "23:05", 300),
                stop("NDLS", "08:30", null, 1384)));
        assertThatThrownBy(() -> RunPlanner.validate(bad)).isInstanceOfSatisfying(PlanningException.class, e ->
                assertThat(e.problems()).contains(
                        "stop 1 (MMCT): missing departure time",
                        "stop 2 (BRC): missing arrival time",
                        "stop 3 (BRC): station appears twice",
                        "stop 3 (BRC): distance must increase along the route"));
        assertThatThrownBy(() -> RunPlanner.validate(new Timetable("1295", "x", overnight().stops())))
                .hasMessageContaining("5 digits");
        assertThatThrownBy(() -> RunPlanner.validate(new Timetable("12951", "x", List.of(stop("bad code!", null, "10:00", 0),
                stop("NDLS", "12:00", null, 100))))).hasMessageContaining("station code");
    }

    @Test
    void an_impossible_speed_means_the_timetable_is_wrong() {
        // 1000 km in 1 hour: really a missed day, or a typo.
        Timetable tt = new Timetable("12345", "x", List.of(stop("AAA", null, "10:00", 0), stop("BBB", "11:00", null, 1000)));
        assertThatThrownBy(() -> plan(tt, List.of(new CoachSpec(TravelClass.SLEEPER, 1))))
                .hasMessageContaining("1000 km/h");
    }

    @Test
    void past_and_far_future_dates_are_date_specific_skips() {
        assertThatThrownBy(() -> RunPlanner.plan(overnight(), LocalDate.of(2026, 11, 1), List.of(new CoachSpec(TravelClass.SLEEPER, 1)),
                LAYOUTS, Options.DEFAULTS, NOW)).isInstanceOf(DateNotPlannable.class);
        assertThatThrownBy(() -> RunPlanner.plan(overnight(), LocalDate.of(2027, 6, 1), List.of(new CoachSpec(TravelClass.SLEEPER, 1)),
                LAYOUTS, Options.DEFAULTS, NOW)).isInstanceOf(DateNotPlannable.class).hasMessageContaining("120 days");
    }

    @Test
    void coach_plans_are_checked() {
        assertThatThrownBy(() -> plan(overnight(), List.of(new CoachSpec(TravelClass.CHAIR_CAR, 2))))
                .isNotInstanceOf(DateNotPlannable.class).hasMessageContaining("LHB_CC is missing from berth_layout"); // every class has a kind since Phase 6
        assertThatThrownBy(() -> plan(overnight(), List.of(new CoachSpec(TravelClass.SLEEPER, 20), new CoachSpec(TravelClass.THIRD_AC, 5))))
                .hasMessageContaining("at most 24 coaches");
        assertThatThrownBy(() -> plan(overnight(), List.of(new CoachSpec(TravelClass.SLEEPER, 1), new CoachSpec(TravelClass.SLEEPER, 2))))
                .hasMessageContaining("listed twice");
        assertThatThrownBy(() -> plan(overnight(), new ArrayList<>())).hasMessageContaining("at least one class");
        assertThatThrownBy(() -> RunPlanner.plan(overnight(), DATE, List.of(new CoachSpec(TravelClass.SLEEPER, 1)),
                Map.of(), Options.DEFAULTS, NOW)).hasMessageContaining("missing from berth_layout");
    }
}
