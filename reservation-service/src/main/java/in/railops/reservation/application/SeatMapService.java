package in.railops.reservation.application;

import in.railops.reservation.adapter.out.persistence.RunDirectoryRepository;
import in.railops.reservation.adapter.out.persistence.SeatMapRepository;
import in.railops.reservation.adapter.out.persistence.SeatMapRepository.BerthRow;
import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.error.ErrorCode;
import in.railops.reservation.domain.model.SegmentRange;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 6: live seat map for passengers. Read-only and public like availability; it shows which berths are free for
 * a journey, never who booked them. RailOps' own bookings only: it is not Indian Railways' occupancy.
 */
@Service
public class SeatMapService {

    public enum State { FREE, RAC_HALF, TAKEN }

    public record Berth(int no, String type, State state) {
    }

    public record Coach(String label, List<Berth> berths, int free) {
    }

    public record SeatMap(long runId, String classCode, int fromSeq, int toSeq, List<Coach> coaches) {
    }

    private final RunDirectoryRepository runs;
    private final SeatMapRepository seats;

    public SeatMapService(RunDirectoryRepository runs, SeatMapRepository seats) {
        this.runs = runs;
        this.seats = seats;
    }

    @Transactional(readOnly = true)
    public SeatMap map(long runId, String classCode, int fromSeq, int toSeq) {
        var run = runs.byId(runId).orElseThrow(() -> new DomainException(ErrorCode.RES_RUN_NOT_FOUND, "train run not found"));
        int lastSeq = runs.stops(runId).stream().mapToInt(RunDirectoryRepository.StopRow::seq).max().orElse(0);
        if (fromSeq < 0 || toSeq <= fromSeq || toSeq > lastSeq) {
            throw new DomainException(ErrorCode.RES_INVALID_JOURNEY, "choose a boarding stop before the destination");
        }
        Map<String, List<Berth>> byCoach = new LinkedHashMap<>();
        for (BerthRow r : seats.berths(run.runDate(), runId, classCode, new SegmentRange(fromSeq, toSeq))) {
            State st = r.taken() >= 2 ? State.TAKEN : r.taken() == 1 ? State.RAC_HALF : State.FREE;
            byCoach.computeIfAbsent(r.coachLabel(), k -> new ArrayList<>()).add(new Berth(r.berthNo(), r.berthType(), st));
        }
        List<Coach> coaches = new ArrayList<>();
        byCoach.forEach((label, berths) -> coaches.add(new Coach(label, List.copyOf(berths),
                (int) berths.stream().filter(b -> b.state() == State.FREE).count())));
        return new SeatMap(runId, classCode, fromSeq, toSeq, List.copyOf(coaches));
    }
}
