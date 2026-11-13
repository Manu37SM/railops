package in.railops.reservation.application;

import in.railops.common.messaging.OutboxWriter;
import in.railops.reservation.adapter.out.persistence.AllocationRepository;
import in.railops.reservation.adapter.out.persistence.AllocationRepository.LiveAllocation;
import in.railops.reservation.adapter.out.persistence.BookingRepository;
import in.railops.reservation.adapter.out.persistence.BookingRepository.PassengerRow;
import in.railops.reservation.adapter.out.persistence.InventoryRepository;
import in.railops.reservation.adapter.out.persistence.InventoryRepository.SegmentRow;
import in.railops.reservation.adapter.out.persistence.RunRepository;
import in.railops.reservation.adapter.out.persistence.WaitlistRepository;
import in.railops.reservation.adapter.out.persistence.WaitlistRepository.WaitingEntry;
import in.railops.reservation.application.AllocationEngine.Assignment;
import in.railops.reservation.application.AllocationEngine.Ctx;
import in.railops.reservation.contracts.ReservationEvents;
import in.railops.reservation.contracts.ReservationEvents.InventoryChanged;
import in.railops.reservation.contracts.ReservationEvents.PassengerPromoted;
import in.railops.reservation.domain.model.PassengerSpec;
import in.railops.reservation.domain.model.PassengerStatus;
import in.railops.reservation.domain.model.Quota;
import in.railops.reservation.domain.model.SegmentRange;
import in.railops.reservation.domain.model.TravelClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Waitlist promotion for one pool (run, class, quota).
 *
 * <p><b>Revision of Phase 4.</b> Phase 4 used an advisory lock and then locked counters per entry's
 * range. That deadlocks: promoting WL#1 on [5,8) then WL#2 on [0,3) acquires counter rows out of
 * seg order while an allocator holding [0,8) waits on segment 5. Promotion now locks the WHOLE pool's
 * counters once, in seg order, up front. That also serializes promoters, so the advisory lock is gone.
 * Cost: allocators for this pool wait for one bounded promotion batch (≤ {@value #BATCH} promotions).
 */
@Service
public class PromotionService {

    private static final Logger log = LoggerFactory.getLogger(PromotionService.class);
    static final int BATCH = 50;

    private final RunRepository runs;
    private final InventoryRepository inventory;
    private final AllocationRepository allocations;
    private final WaitlistRepository waitlist;
    private final BookingRepository bookings;
    private final AllocationEngine engine;
    private final OutboxWriter outbox;
    private final TransactionRunner tx;

    public PromotionService(RunRepository runs, InventoryRepository inventory, AllocationRepository allocations,
                            WaitlistRepository waitlist, BookingRepository bookings, AllocationEngine engine,
                            OutboxWriter outbox, TransactionRunner tx) {
        this.runs = runs;
        this.inventory = inventory;
        this.allocations = allocations;
        this.waitlist = waitlist;
        this.bookings = bookings;
        this.engine = engine;
        this.outbox = outbox;
        this.tx = tx;
    }

    /** Promotes until a batch comes back short. Idempotent: a re-run finds nothing more to do. */
    public int promoteAll(long runId, String classCode, String quotaCode) {
        return promoteAll(runId, classCode, quotaCode, false);
    }

    /**
     * @param chartPass true when called by ChartService: runs while status is CHART_1/CHART_2. Event-driven
     *                  promotion stops at chart time so that only the chart decides final allocation.
     */
    public int promoteAll(long runId, String classCode, String quotaCode, boolean chartPass) {
        Optional<RunRepository.RunView> run = runs.find(runId);
        java.util.Set<String> allowed = chartPass ? java.util.Set.of("CHART_1", "CHART_2") : java.util.Set.of("SCHEDULED");
        if (run.isEmpty() || !allowed.contains(run.get().status())) {
            return 0;
        }
        LocalDate runDate = run.get().runDate();
        int total = 0;
        int batch;
        do {
            batch = tx.inNewTransaction(() -> promoteBatch(runId, runDate, classCode, quotaCode));
            total += batch;
        } while (batch == BATCH);
        if (total > 0) {
            log.info("promoted {} passengers in {}/{}/{}", total, runId, classCode, quotaCode);
        }
        return total;
    }

    private int promoteBatch(long runId, LocalDate runDate, String cls, String quota) {
        List<SegmentRow> rows = inventory.lockPool(runId, cls, quota);
        if (rows.isEmpty()) {
            return 0;
        }
        int maxSeg = rows.stream().mapToInt(SegmentRow::segIdx).max().orElse(0);
        int[] cnfFree = new int[maxSeg + 1];
        int[] racFree = new int[maxSeg + 1];
        for (SegmentRow r : rows) {
            cnfFree[r.segIdx()] = r.cnfFree();
            racFree[r.segIdx()] = r.racFree();
        }
        TravelClass travelClass = TravelClass.fromCode(cls);
        Quota q = Quota.valueOf(quota);
        int promoted = 0;

        // Pass 1: RAC -> CNF, oldest RAC first.
        for (LiveAllocation rac : allocations.confirmedRacInPool(runDate, runId, cls, quota)) {
            if (promoted >= BATCH) {
                return promoted;
            }
            if (min(cnfFree, rac.range()) < 1) {
                continue;
            }
            PassengerRow p = bookings.passengerById(runDate, rac.passengerId()).orElseThrow();
            Ctx ctx = new Ctx(runId, runDate, travelClass, q, rac.range());
            Optional<Assignment> placed = engine.placeOne(ctx, spec(p), p.id(), false);
            if (placed.isEmpty()) {
                continue; // fragmentation: counters free per segment, but no single berth spans the range
            }
            allocations.releaseById(runDate, rac.id(), "PROMOTED_UP");
            inventory.adjust(runId, cls, quota, rac.range(), +1, -1);
            add(cnfFree, rac.range(), -1);
            add(racFree, rac.range(), +1);
            record(runDate, p, placed.get());
            promoted++;
        }

        // Pass 2: WL -> CNF, else WL -> RAC, in WL order; entries that don't fit are skipped (ordered greedy).
        for (WaitingEntry w : waitlist.waiting(runDate, runId, cls, quota, 500)) {
            if (promoted >= BATCH) {
                return promoted;
            }
            boolean cnfPossible = min(cnfFree, w.range()) >= 1;
            boolean racPossible = min(racFree, w.range()) >= 1;
            if (!cnfPossible && !racPossible) {
                continue;
            }
            PassengerRow p = bookings.passengerById(runDate, w.passengerId()).orElseThrow();
            Ctx ctx = new Ctx(runId, runDate, travelClass, q, w.range());
            Optional<Assignment> placed = cnfPossible ? engine.placeOne(ctx, spec(p), p.id(), false) : Optional.empty();
            if (placed.isEmpty() && racPossible) {
                placed = engine.placeOne(ctx, spec(p), p.id(), true);
            }
            if (placed.isEmpty()) {
                continue;
            }
            boolean toCnf = placed.get().status() == PassengerStatus.CNF;
            inventory.adjust(runId, cls, quota, w.range(), toCnf ? 1 : 0, toCnf ? 0 : 1);
            add(toCnf ? cnfFree : racFree, w.range(), -1);
            waitlist.markPromoted(runDate, w.id(), toCnf ? "PROMOTED_CNF" : "PROMOTED_RAC");
            record(runDate, p, placed.get());
            promoted++;
        }

        if (promoted > 0) {
            outbox.append(ReservationEvents.TOPIC_INVENTORY, "TrainRun", Long.toString(runId), "InventoryChanged",
                    new InventoryChanged(runId, cls, quota, 0, maxSeg + 1, "PROMOTION"), Map.of());
        }
        return promoted;
    }

    private void record(LocalDate runDate, PassengerRow p, Assignment a) {
        bookings.updatePassengerStatus(runDate, p.id(), a.status(), a.statusText());
        String pnr = bookings.pnrOfPassenger(runDate, p.id()).orElseThrow();
        outbox.append(ReservationEvents.TOPIC_BOOKING, "Booking", pnr, "PassengerPromoted",
                new PassengerPromoted(pnr, p.id(), a.status().name(), a.statusText()), Map.of());
    }

    private static PassengerSpec spec(PassengerRow p) {
        return new PassengerSpec(p.seqNo(), p.name(), p.age(), p.gender(), p.berthPref(), p.berthRequired());
    }

    private static int min(int[] free, SegmentRange r) {
        int m = Integer.MAX_VALUE;
        for (int i = r.from(); i < r.to(); i++) {
            m = Math.min(m, i < free.length ? free[i] : 0);
        }
        return m;
    }

    private static void add(int[] arr, SegmentRange r, int delta) {
        for (int i = r.from(); i < r.to() && i < arr.length; i++) {
            arr[i] += delta;
        }
    }
}
