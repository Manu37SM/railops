package in.railops.reservation.application;

import in.railops.reservation.adapter.out.persistence.RunDirectoryRepository;
import in.railops.reservation.adapter.out.persistence.RunDirectoryRepository.RunRow;
import in.railops.reservation.adapter.out.persistence.RunDirectoryRepository.StopRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Phase 5: lets a passenger app (the RailLens website and Android app) go from "train 12951 on 20 Nov" to the run id
 * and stop numbers that availability and booking take. Public like availability; nothing here is personal.
 */
@Service
public class RunDirectoryService {

    public record RunSummary(long runId, String trainNo, LocalDate runDate, String status, Instant bookingOpensAt,
                             List<String> classes, List<StopRow> stops) {
    }

    private final RunDirectoryRepository runs;

    public RunDirectoryService(RunDirectoryRepository runs) {
        this.runs = runs;
    }

    @Transactional(readOnly = true)
    public Optional<RunSummary> find(String trainNo, LocalDate date) {
        return runs.byTrainAndDate(trainNo, date).map(this::summary);
    }

    @Transactional(readOnly = true)
    public Optional<RunSummary> find(long runId) {
        return runs.byId(runId).map(this::summary);
    }

    private RunSummary summary(RunRow r) {
        return new RunSummary(r.id(), r.trainNo(), r.runDate(), r.status(), r.bookingOpensAt(),
                runs.classes(r.id()), runs.stops(r.id()));
    }
}
