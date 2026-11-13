package in.railops.reservation.application.schedule;

import in.railops.reservation.adapter.out.persistence.RunImportRepository;
import in.railops.reservation.application.StaffAudit;
import in.railops.reservation.application.TransactionRunner;
import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.error.ErrorCode;
import in.railops.reservation.domain.policy.RunPlanner;
import in.railops.reservation.domain.policy.RunPlanner.CoachSpec;
import in.railops.reservation.domain.policy.RunPlanner.Layout;
import in.railops.reservation.domain.policy.RunPlanner.Options;
import in.railops.reservation.domain.policy.RunPlanner.PlanningException;
import in.railops.reservation.domain.policy.RunPlanner.RunPlan;
import in.railops.reservation.domain.policy.RunPlanner.Timetable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Creates dated train runs from a RailLens timetable (Phase 3B). Typical use: an admin opens the next month of a
 * train for booking with its coach plan ("12951, 1-31 Dec, 2 x 2A, 6 x 3A, 8 x SL").
 *
 * <p>Each date is its own transaction and its own result: a date that already has a run is reported
 * ALREADY_EXISTS and left untouched (it may have bookings), a date that can't be planned (e.g. in the past) is
 * SKIPPED with the reason, the rest are CREATED. Re-running the same request is therefore safe.
 */
@Service
public class RunImportService {

    private static final Logger log = LoggerFactory.getLogger(RunImportService.class);
    static final int MAX_DATES = 62;

    public record ImportCommand(String trainNo, LocalDate fromDate, LocalDate toDate, List<CoachSpec> consist,
                                Integer tatkalPercent, Integer waitlistPercent) {
    }

    public enum Outcome { CREATED, ALREADY_EXISTS, SKIPPED }

    public record DateResult(LocalDate runDate, Outcome outcome, Long trainRunId, String detail) {
    }

    public record ImportResult(String trainNo, String trainName, int stops, List<DateResult> dates) {
    }

    private final TimetablePort timetables;
    private final RunImportRepository runs;
    private final TransactionRunner tx;
    private final StaffAudit audit;
    private final Clock clock;

    public RunImportService(TimetablePort timetables, RunImportRepository runs, TransactionRunner tx, StaffAudit audit,
                            ObjectProvider<Clock> clock) {
        this.timetables = timetables;
        this.runs = runs;
        this.tx = tx;
        this.audit = audit;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    public ImportResult importRuns(ImportCommand cmd, String actor) {
        if (cmd.fromDate() == null || cmd.toDate() == null || cmd.toDate().isBefore(cmd.fromDate())) {
            throw invalid(List.of("toDate must be on or after fromDate"));
        }
        long days = ChronoUnit.DAYS.between(cmd.fromDate(), cmd.toDate()) + 1;
        if (days > MAX_DATES) {
            throw invalid(List.of("at most " + MAX_DATES + " dates per import, asked for " + days));
        }
        Timetable timetable = timetables.fetch(cmd.trainNo())
                .orElseThrow(() -> new DomainException(ErrorCode.RES_TIMETABLE_NOT_FOUND,
                        "train " + cmd.trainNo() + " is not in the RailLens timetable"));
        if (!Objects.equals(timetable.trainNo(), cmd.trainNo())) {
            throw invalid(List.of("RailLens answered for train " + timetable.trainNo() + ", not " + cmd.trainNo()));
        }
        try {
            RunPlanner.validate(timetable);
        } catch (PlanningException e) {
            throw invalid(e.problems());
        }
        Options options = new Options(
                cmd.tatkalPercent() == null ? Options.DEFAULTS.tatkalPercent() : cmd.tatkalPercent(),
                cmd.waitlistPercent() == null ? Options.DEFAULTS.waitlistPercent() : cmd.waitlistPercent(),
                Options.DEFAULTS.advanceDays(), Options.DEFAULTS.horizonDays());
        Map<String, Layout> layouts = runs.layouts(cmd.consist() == null ? List.of() : cmd.consist().stream()
                .map(c -> RunPlanner.COACH_KINDS.get(c.travelClass())).filter(Objects::nonNull)
                .map(RunPlanner.CoachKind::coachType).distinct().toList());

        List<DateResult> results = new ArrayList<>();
        for (LocalDate date = cmd.fromDate(); !date.isAfter(cmd.toDate()); date = date.plusDays(1)) {
            RunPlan plan;
            try {
                plan = RunPlanner.plan(timetable, date, cmd.consist(), layouts, options, clock.instant());
            } catch (RunPlanner.DateNotPlannable e) {
                results.add(new DateResult(date, Outcome.SKIPPED, null, String.join("; ", e.problems())));
                continue;
            } catch (PlanningException e) {
                throw invalid(e.problems());   // a problem with the request itself: the same for every date
            }
            LocalDate runDate = date;
            Long created = tx.inNewTransaction(() -> runs.insert(plan).orElse(null));
            if (created == null) {
                Long existing = runs.existingRun(cmd.trainNo(), runDate).orElse(null);
                results.add(new DateResult(runDate, Outcome.ALREADY_EXISTS, existing, "left unchanged"));
            } else {
                audit.action(actor, "IMPORT_RUN", created);
                results.add(new DateResult(runDate, Outcome.CREATED, created, null));
            }
        }
        log.info("run import by {}: train {} {}..{} -> {}", actor, cmd.trainNo(), cmd.fromDate(), cmd.toDate(),
                results.stream().map(r -> r.outcome().name()).toList());
        return new ImportResult(timetable.trainNo(), timetable.trainName(), timetable.stops().size(), results);
    }

    private static DomainException invalid(List<String> problems) {
        return new DomainException(ErrorCode.RES_TIMETABLE_INVALID, String.join("; ", problems));
    }
}
