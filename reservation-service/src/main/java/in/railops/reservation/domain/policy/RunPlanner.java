package in.railops.reservation.domain.policy;

import in.railops.reservation.domain.model.Quota;
import in.railops.reservation.domain.model.TravelClass;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns a published timetable (from RailLens) plus a coach plan into everything one dated train run needs: stops
 * with real timestamps, coaches, seat inventory per class/quota/segment and waitlist limits (Phase 3B).
 *
 * <p>Pure: no I/O and no clock (now is passed in), so every rule is unit-tested.
 *
 * <p>Rules:
 * <ul>
 *   <li><b>Times</b> are Indian Standard Time. Timetables carry clock times only, so each stop's date is worked out
 *       by walking forward from the origin: an arrival or departure earlier in the day than the previous event is on
 *       the next day. A leg whose implied average speed is outside 5-200 km/h is refused as a broken timetable
 *       (that also catches a missed midnight, i.e. a leg longer than a day).</li>
 *   <li><b>Seats</b> come from the coach layouts (berth_layout): side-lower berths are RAC (two passengers each),
 *       every other berth is a confirmed seat. Tatkal gets {@code tatkalPercent} of the confirmed seats (rounded
 *       down), the general quota the rest and all RAC. So the counters never promise more than the physical berths;
 *       the allocator's no-double-berth constraint stays the hard limit.</li>
 *   <li><b>Booking window</b>: general opens {@code advanceDays} before departure (Indian Railways: 60), Tatkal at
 *       10:00 the day before departure, chart 1 four hours and chart 2 thirty minutes before departure.</li>
 * </ul>
 */
public final class RunPlanner {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Pattern TRAIN_NO = Pattern.compile("\\d{5}");
    private static final Pattern STATION = Pattern.compile("[A-Z0-9]{1,5}");
    static final int MAX_COACHES = 24;
    static final int MAX_STOPS = 200;
    static final double MIN_KMH = 5;
    static final double MAX_KMH = 200;
    static final LocalTime TATKAL_OPENS = LocalTime.of(10, 0);

    /** Coach type and label prefix per class: only classes with a berth layout in R__berth_layouts.sql. */
    public static final Map<TravelClass, CoachKind> COACH_KINDS = Map.of(
            TravelClass.FIRST_AC, new CoachKind("LHB_1A", "H"),
            TravelClass.SECOND_AC, new CoachKind("LHB_2A", "A"),
            TravelClass.THIRD_AC, new CoachKind("LHB_3A", "B"),
            TravelClass.THIRD_AC_ECONOMY, new CoachKind("LHB_3E", "M"),
            TravelClass.SLEEPER, new CoachKind("LHB_SL", "S"),
            TravelClass.CHAIR_CAR, new CoachKind("LHB_CC", "C"),
            TravelClass.EXECUTIVE_CHAIR, new CoachKind("LHB_EC", "E"),
            TravelClass.SECOND_SITTING, new CoachKind("LHB_2S", "D"));

    /**
     * Standard coach sets (Phase 6), so staff pick a kind of train instead of typing counts. Typical reserved
     * coaches only (no pantry, luggage or general coaches); real trains vary.
     */
    public static final Map<String, List<CoachSpec>> STANDARD_CONSISTS = Map.of(
            "RAJDHANI", List.of(new CoachSpec(TravelClass.FIRST_AC, 1), new CoachSpec(TravelClass.SECOND_AC, 4),
                    new CoachSpec(TravelClass.THIRD_AC, 11)),
            "DURONTO", List.of(new CoachSpec(TravelClass.FIRST_AC, 1), new CoachSpec(TravelClass.SECOND_AC, 3),
                    new CoachSpec(TravelClass.THIRD_AC, 8), new CoachSpec(TravelClass.SLEEPER, 4)),
            "MAIL_EXPRESS", List.of(new CoachSpec(TravelClass.SECOND_AC, 2), new CoachSpec(TravelClass.THIRD_AC, 6),
                    new CoachSpec(TravelClass.THIRD_AC_ECONOMY, 1), new CoachSpec(TravelClass.SLEEPER, 8),
                    new CoachSpec(TravelClass.SECOND_SITTING, 2)),
            "SHATABDI", List.of(new CoachSpec(TravelClass.EXECUTIVE_CHAIR, 2), new CoachSpec(TravelClass.CHAIR_CAR, 12)),
            "VANDE_BHARAT", List.of(new CoachSpec(TravelClass.EXECUTIVE_CHAIR, 2), new CoachSpec(TravelClass.CHAIR_CAR, 14)),
            "JAN_SHATABDI", List.of(new CoachSpec(TravelClass.CHAIR_CAR, 4), new CoachSpec(TravelClass.SECOND_SITTING, 10)),
            "GARIB_RATH", List.of(new CoachSpec(TravelClass.THIRD_AC_ECONOMY, 12)));

    public record CoachKind(String coachType, String labelPrefix) {
    }

    public record TimetableStop(String stationCode, LocalTime arrival, LocalTime departure, Integer distanceKm) {
    }

    public record Timetable(String trainNo, String trainName, List<TimetableStop> stops) {
    }

    public record CoachSpec(TravelClass travelClass, int count) {
    }

    /** Berths per coach of a type, and how many of them are RAC (side-lower) berths. */
    public record Layout(int berths, int racBerths) {
    }

    public record Options(int tatkalPercent, int waitlistPercent, int advanceDays, int horizonDays) {
        public static final Options DEFAULTS = new Options(10, 50, 60, 120);
    }

    public record PlannedStop(int seq, String stationCode, OffsetDateTime arrival, OffsetDateTime departure, int distanceKm) {
    }

    public record PlannedCoach(String label, String coachType, String classCode, int position) {
    }

    public record PlannedPool(String classCode, Quota quota, int cnfCapacity, int racCapacity, int waitlistCap) {
    }

    public record RunPlan(String trainNo, LocalDate runDate, OffsetDateTime departure, OffsetDateTime bookingOpensAt,
                          OffsetDateTime tatkalOpensAt, OffsetDateTime chart1DueAt, OffsetDateTime chart2DueAt,
                          List<PlannedStop> stops, List<PlannedCoach> coaches, List<PlannedPool> pools) {

        public int segments() {
            return stops.size() - 1;
        }
    }

    /** Why a timetable or request can't become a run. {@code problems} are shown to staff as-is. */
    public static class PlanningException extends RuntimeException {
        private final List<String> problems;

        public PlanningException(List<String> problems) {
            super(String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    /** The request is fine but this particular date can't have a run (in the past, or beyond the horizon). */
    public static final class DateNotPlannable extends PlanningException {
        public DateNotPlannable(String problem) {
            super(List.of(problem));
        }
    }

    private RunPlanner() {
    }

    /** Checks the timetable once, independent of any date. Returns it unchanged, or throws with every problem. */
    public static Timetable validate(Timetable tt) {
        List<String> problems = new ArrayList<>();
        if (tt.trainNo() == null || !TRAIN_NO.matcher(tt.trainNo()).matches()) {
            problems.add("train number must be 5 digits, got '" + tt.trainNo() + "'");
        }
        List<TimetableStop> stops = tt.stops() == null ? List.of() : tt.stops();
        if (stops.size() < 2) {
            problems.add("a train needs at least 2 stops, timetable has " + stops.size());
        }
        if (stops.size() > MAX_STOPS) {
            problems.add("more than " + MAX_STOPS + " stops");
        }
        Set<String> seen = new HashSet<>();
        Integer lastKm = null;
        for (int i = 0; i < stops.size(); i++) {
            TimetableStop s = stops.get(i);
            String where = "stop " + (i + 1) + " (" + s.stationCode() + ")";
            if (s.stationCode() == null || !STATION.matcher(s.stationCode()).matches()) {
                problems.add(where + ": station code must be 1-5 capital letters or digits");
            } else if (!seen.add(s.stationCode())) {
                problems.add(where + ": station appears twice");
            }
            boolean first = i == 0;
            boolean last = i == stops.size() - 1;
            if (!first && s.arrival() == null) {
                problems.add(where + ": missing arrival time");
            }
            if (!last && s.departure() == null) {
                problems.add(where + ": missing departure time");
            }
            if (s.distanceKm() == null || s.distanceKm() < 0) {
                problems.add(where + ": missing distance");
            } else {
                if (first && s.distanceKm() != 0) {
                    problems.add(where + ": origin distance must be 0");
                }
                if (lastKm != null && s.distanceKm() <= lastKm) {
                    problems.add(where + ": distance must increase along the route");
                }
                lastKm = s.distanceKm();
            }
        }
        if (!problems.isEmpty()) {
            throw new PlanningException(problems);
        }
        return tt;
    }

    public static RunPlan plan(Timetable tt, LocalDate runDate, List<CoachSpec> consist, Map<String, Layout> layouts,
                               Options o, Instant now) {
        validate(tt);
        List<String> problems = new ArrayList<>();
        checkOptions(o, problems);
        checkConsist(consist, layouts, problems);
        if (!problems.isEmpty()) {
            throw new PlanningException(problems);
        }

        List<PlannedStop> stops = times(tt, runDate);
        OffsetDateTime departure = stops.getFirst().departure();
        LocalDate today = LocalDate.ofInstant(now, IST);
        if (!departure.toInstant().isAfter(now.plus(Duration.ofHours(1)))) {
            throw new DateNotPlannable("departs in the past or within the hour");
        }
        if (runDate.isAfter(today.plusDays(o.horizonDays()))) {
            throw new DateNotPlannable("more than " + o.horizonDays() + " days ahead");
        }

        List<PlannedCoach> coaches = new ArrayList<>();
        List<PlannedPool> pools = new ArrayList<>();
        int position = 1;
        for (CoachSpec spec : consist) {
            CoachKind kind = COACH_KINDS.get(spec.travelClass());
            Layout layout = layouts.get(kind.coachType());
            for (int n = 1; n <= spec.count(); n++) {
                coaches.add(new PlannedCoach(kind.labelPrefix() + n, kind.coachType(), spec.travelClass().code, position++));
            }
            int racBerths = spec.count() * layout.racBerths();
            int confirmed = spec.count() * layout.berths() - racBerths;
            int tatkal = confirmed * o.tatkalPercent() / 100;
            int general = confirmed - tatkal;
            pools.add(new PlannedPool(spec.travelClass().code, Quota.GN, general, 2 * racBerths,
                    Math.max(1, ceilPercent(general, o.waitlistPercent()))));
            if (tatkal > 0) {
                pools.add(new PlannedPool(spec.travelClass().code, Quota.TQ, tatkal, 0,
                        Math.max(1, ceilPercent(tatkal, o.waitlistPercent()))));
            }
        }

        OffsetDateTime tatkalOpens = departure.toLocalDate().minusDays(1).atTime(TATKAL_OPENS).atZone(IST).toOffsetDateTime();
        return new RunPlan(tt.trainNo(), runDate, departure, departure.minusDays(o.advanceDays()), tatkalOpens,
                departure.minusHours(4), departure.minusMinutes(30), List.copyOf(stops), List.copyOf(coaches),
                List.copyOf(pools));
    }

    /** Clock times to timestamps: each event is the first occurrence of its clock time at or after the previous one. */
    static List<PlannedStop> times(Timetable tt, LocalDate runDate) {
        List<TimetableStop> in = tt.stops();
        List<PlannedStop> out = new ArrayList<>();
        LocalDateTime previous = runDate.atTime(in.getFirst().departure());
        int previousKm = 0;
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            TimetableStop s = in.get(i);
            LocalDateTime arr = null;
            LocalDateTime dep = null;
            if (i > 0) {
                arr = nextAtOrAfter(previous, s.arrival());
                double hours = Duration.between(previous, arr).toMinutes() / 60.0;
                double km = s.distanceKm() - previousKm;
                double kmh = hours == 0 ? Double.POSITIVE_INFINITY : km / hours;
                if (kmh < MIN_KMH || kmh > MAX_KMH) {
                    problems.add("leg to stop " + (i + 1) + " (" + s.stationCode() + ") implies "
                            + (Double.isInfinite(kmh) ? "zero travel time" : Math.round(kmh) + " km/h")
                            + ": check the timetable");
                }
                previous = arr;
            }
            if (i < in.size() - 1) {
                dep = i == 0 ? previous : nextAtOrAfter(previous, s.departure());
                previous = dep;
            }
            previousKm = s.distanceKm();
            out.add(new PlannedStop(i, s.stationCode(), ist(arr), ist(dep), s.distanceKm()));
        }
        if (!problems.isEmpty()) {
            throw new PlanningException(problems);
        }
        return out;
    }

    private static LocalDateTime nextAtOrAfter(LocalDateTime previous, LocalTime time) {
        LocalDateTime candidate = previous.toLocalDate().atTime(time);
        return candidate.isBefore(previous) ? candidate.plusDays(1) : candidate;
    }

    private static OffsetDateTime ist(LocalDateTime t) {
        return t == null ? null : t.atZone(IST).toOffsetDateTime();
    }

    private static int ceilPercent(int value, int percent) {
        return (value * percent + 99) / 100;
    }

    private static void checkOptions(Options o, List<String> problems) {
        if (o.tatkalPercent() < 0 || o.tatkalPercent() > 50) {
            problems.add("tatkalPercent must be 0-50");
        }
        if (o.waitlistPercent() < 0 || o.waitlistPercent() > 200) {
            problems.add("waitlistPercent must be 0-200");
        }
        if (o.advanceDays() < 1 || o.advanceDays() > 120) {
            problems.add("advanceDays must be 1-120");
        }
    }

    private static void checkConsist(List<CoachSpec> consist, Map<String, Layout> layouts, List<String> problems) {
        if (consist == null || consist.isEmpty()) {
            problems.add("choose at least one class of coaches");
            return;
        }
        Map<TravelClass, Integer> perClass = new LinkedHashMap<>();
        int total = 0;
        for (CoachSpec c : consist) {
            if (c.travelClass() == null) {
                problems.add("coach class is missing");
                continue;
            }
            CoachKind kind = COACH_KINDS.get(c.travelClass());
            if (kind == null) {
                problems.add("class " + c.travelClass().code + " has no coach layout");
            } else if (!layouts.containsKey(kind.coachType())) {
                problems.add("coach layout " + kind.coachType() + " is missing from berth_layout");
            }
            if (c.count() < 1) {
                problems.add("class " + c.travelClass().code + ": at least 1 coach");
            }
            if (perClass.merge(c.travelClass(), 1, Integer::sum) > 1) {
                problems.add("class " + c.travelClass().code + " is listed twice");
            }
            total += Math.max(c.count(), 0);
        }
        if (total > MAX_COACHES) {
            problems.add("at most " + MAX_COACHES + " coaches per train, got " + total);
        }
    }
}
