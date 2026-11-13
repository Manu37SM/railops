package in.railops.reservation.application;

import in.railops.reservation.adapter.out.persistence.AllocationRepository;
import in.railops.reservation.adapter.out.persistence.AllocationRepository.BerthCandidate;
import in.railops.reservation.adapter.out.persistence.AllocationRepository.NewAllocation;
import in.railops.reservation.adapter.out.persistence.InventoryRepository;
import in.railops.reservation.adapter.out.persistence.InventoryRepository.SegmentRow;
import in.railops.reservation.adapter.out.persistence.WaitlistRepository;
import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.error.ErrorCode;
import in.railops.reservation.domain.model.PassengerSpec;
import in.railops.reservation.domain.model.PassengerStatus;
import in.railops.reservation.domain.model.Quota;
import in.railops.reservation.domain.model.SegmentRange;
import in.railops.reservation.domain.model.SlotRange;
import in.railops.reservation.domain.model.TravelClass;
import in.railops.reservation.domain.policy.AllocationPlanner.Plan;
import in.railops.reservation.domain.policy.AllocationPlanner.Planned;
import in.railops.reservation.domain.policy.BerthPreferencePolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Turns a plan (CNF/RAC/WL counts) into physical berths and waitlist numbers.
 *
 * <p><b>Fragmentation.</b> Counters guarantee one free unit on <i>each</i> segment, not one berth free
 * across the <i>whole</i> range: berth X may be free on segments 0-1 and berth Y on 2-3. So a planned
 * CNF can legitimately find no berth. That passenger is downgraded to RAC, then WL, and counted in
 * {@code allocation.fragmentation.downgrade}. Counters are adjusted from what was actually placed.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class AllocationEngine {

    public record Ctx(long runId, LocalDate runDate, TravelClass cls, Quota quota, SegmentRange range) {
    }

    public record Free(int cnf, int racSlots) {
    }

    public record Assignment(PassengerSpec spec, long passengerId, PassengerStatus status, String statusText) {
    }

    private record Placed(BerthCandidate berth, SlotRange slot) {
    }

    private static final int REFETCH_LIMIT = 400;

    private final InventoryRepository inventory;
    private final AllocationRepository allocations;
    private final WaitlistRepository waitlist;
    private final BerthPreferencePolicy prefs = new BerthPreferencePolicy();
    private final int holdMinutes;
    private final Counter fragmentationDowngrades;
    private final io.micrometer.core.instrument.Timer lockHold;

    public AllocationEngine(InventoryRepository inventory, AllocationRepository allocations,
                            WaitlistRepository waitlist, MeterRegistry meters,
                            @Value("${railops.booking.hold-minutes:10}") int holdMinutes) {
        this.inventory = inventory;
        this.allocations = allocations;
        this.waitlist = waitlist;
        this.holdMinutes = holdMinutes;
        this.fragmentationDowngrades = Counter.builder("allocation.fragmentation.downgrade")
                .description("planned CNF/RAC passengers that found no single berth across their range")
                .register(meters);
        // THE capacity metric for a hot pool: bookings/s per pool can never exceed 1 / mean(lock hold).
        this.lockHold = io.micrometer.core.instrument.Timer.builder("inventory.pool_lock.hold")
                .description("time from acquiring a pool's counter locks to transaction completion")
                .publishPercentiles(0.5, 0.99).register(meters);
    }

    /** Locks the counters for the journey (seg order) and returns the bottleneck free counts. */
    public Free lockAndMeasure(Ctx ctx) {
        return lockAndMeasure(ctx, java.util.Set.of("SCHEDULED"));
    }

    /** @param allowedStatuses run statuses in which allocating is legitimate for this caller */
    public Free lockAndMeasure(Ctx ctx, java.util.Set<String> allowedStatuses) {
        List<SegmentRow> rows = inventory.lockRange(ctx.runId(), ctx.cls().code, ctx.quota().name(), ctx.range());
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            long acquired = System.nanoTime();
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            lockHold.record(System.nanoTime() - acquired, java.util.concurrent.TimeUnit.NANOSECONDS);
                        }
                    });
        }
        if (rows.size() != ctx.range().length()) {
            throw new DomainException(ErrorCode.RES_RUN_NOT_BOOKABLE,
                    "class " + ctx.cls().code + " / quota " + ctx.quota() + " is not offered on this journey");
        }
        // The pre-check in BookingService read the status WITHOUT locks. Re-read now that we hold counter locks:
        // a run cancellation or chart start flips status only while holding ALL counter locks, so this read is
        // decisive. (Closes the Phase 5 race where a booking could commit after its run was cancelled.)
        String status = inventory.runStatusUnderLock(ctx.runId());
        if (!allowedStatuses.contains(status)) {
            throw new DomainException(ErrorCode.RES_RUN_NOT_BOOKABLE, "run is " + status);
        }
        int cnf = rows.stream().mapToInt(SegmentRow::cnfFree).min().orElse(0);
        int rac = rows.stream().mapToInt(SegmentRow::racFree).min().orElse(0);
        return new Free(cnf, rac);
    }

    /**
     * Executes a plan. Caller must hold the counter locks from {@link #lockAndMeasure}.
     *
     * @param held        true: HELD allocations with expiry (new booking); false: CONFIRMED (late payment)
     * @param wlStatus    PENDING for unpaid bookings, WAITING for paid ones
     */
    public List<Assignment> execute(Ctx ctx, Plan plan, Free free, Map<Integer, Long> passengerIdBySeq,
                                    boolean held, String wlStatus) {
        Map<Integer, Assignment> out = new HashMap<>();
        List<PassengerSpec> wantCnf = byPickOrder(plan, PassengerStatus.CNF);
        List<PassengerSpec> wantRac = byPickOrder(plan, PassengerStatus.RAC);
        List<PassengerSpec> wantWl = new ArrayList<>(plan.passengers().stream()
                .filter(p -> p.status() == PassengerStatus.WL).map(Planned::spec)
                .sorted(Comparator.comparingInt(PassengerSpec::seqNo)).toList());

        // CNF: one candidate fetch for the group, so a family can be seated in one coach.
        int cnfPlaced = 0;
        List<PassengerSpec> downgraded = new ArrayList<>();
        if (!wantCnf.isEmpty()) {
            List<BerthCandidate> pool = groupPool(ctx, wantCnf);
            Long familyCoach = familyCoach(pool, wantCnf.size());
            for (PassengerSpec p : wantCnf) {
                long pid = passengerIdBySeq.get(p.seqNo());
                Optional<Placed> placed = placeFull(ctx, p, pid, pool, familyCoach, held);
                if (placed.isPresent()) {
                    cnfPlaced++;
                    out.put(p.seqNo(), cnfAssignment(p, pid, placed.get().berth()));
                } else {
                    fragmentationDowngrades.increment();
                    downgraded.add(p);
                }
            }
        }

        // RAC: budget is the measured free slots, not the plan, because downgraded CNF passengers
        // (appended after the planned RAC ones) also compete for it. Exceeding real free slots would
        // trip the counter CHECK and abort the whole transaction.
        java.util.Set<Integer> plannedRac = new java.util.HashSet<>();
        wantRac.forEach(p -> plannedRac.add(p.seqNo()));
        List<PassengerSpec> racQueue = new ArrayList<>(wantRac);
        racQueue.addAll(downgraded);
        int racPlaced = 0;
        for (PassengerSpec p : racQueue) {
            long pid = passengerIdBySeq.get(p.seqNo());
            Optional<Placed> placed = racPlaced < free.racSlots() ? placeRac(ctx, p, pid, held) : Optional.empty();
            if (placed.isPresent()) {
                racPlaced++;
                out.put(p.seqNo(), racAssignment(p, pid, placed.get()));
            } else {
                if (plannedRac.contains(p.seqNo()) && racPlaced < free.racSlots()) {
                    fragmentationDowngrades.increment();
                }
                wantWl.add(p);
            }
        }

        // WL: consecutive numbers in passenger order, or regret if the cap is reached.
        if (!wantWl.isEmpty()) {
            wantWl.sort(Comparator.comparingInt(PassengerSpec::seqNo));
            OptionalInt first = waitlist.reserveSequence(ctx.runId(), ctx.cls().code, ctx.quota().name(), wantWl.size());
            if (first.isEmpty()) {
                throw new DomainException(ErrorCode.RES_REGRET, "waitlist is full for this class and quota");
            }
            int seq = first.getAsInt();
            for (PassengerSpec p : wantWl) {
                long pid = passengerIdBySeq.get(p.seqNo());
                waitlist.insert(ctx.runDate(), ctx.runId(), ctx.cls().code, ctx.quota().name(), seq, pid,
                        ctx.range(), wlStatus);
                out.put(p.seqNo(), new Assignment(p, pid, PassengerStatus.WL, "WL/" + seq));
                seq++;
            }
        }

        plan.passengers().stream().filter(p -> p.status() == PassengerStatus.NOSB).forEach(p -> {
            long pid = passengerIdBySeq.get(p.spec().seqNo());
            out.put(p.spec().seqNo(), new Assignment(p.spec(), pid, PassengerStatus.NOSB, "NOSB"));
        });

        inventory.adjust(ctx.runId(), ctx.cls().code, ctx.quota().name(), ctx.range(), cnfPlaced, racPlaced);

        return out.values().stream().sorted(Comparator.comparingInt(a -> a.spec().seqNo())).toList();
    }

    /**
     * Places one already-paid passenger (promotion). Does NOT adjust counters: the promotion
     * service owns the counter arithmetic because it also releases the old allocation.
     */
    public Optional<Assignment> placeOne(Ctx ctx, PassengerSpec spec, long passengerId, boolean rac) {
        if (rac) {
            return placeRac(ctx, spec, passengerId, false).map(pl -> racAssignment(spec, passengerId, pl));
        }
        List<BerthCandidate> pool = new ArrayList<>(allocations.candidates(ctx.runDate(), ctx.runId(),
                ctx.cls().code, ctx.range(), SlotRange.FULL, prefs.preferredTypes(spec, ctx.cls()), 24));
        return placeFull(ctx, spec, passengerId, pool, null, false)
                .map(pl -> cnfAssignment(spec, passengerId, pl.berth()));
    }

    // ------------------------------------------------------------------ internals

    private Optional<Placed> placeFull(Ctx ctx, PassengerSpec p, long pid, List<BerthCandidate> pool,
                                       Long familyCoach, boolean held) {
        List<String> mine = prefs.preferredTypes(p, ctx.cls());
        for (int round = 0; round < 2; round++) {
            List<BerthCandidate> ordered = new ArrayList<>(pool);
            ordered.sort(Comparator
                    .comparing((BerthCandidate b) -> familyCoach == null || b.runCoachId() != familyCoach)
                    .thenComparingInt(b -> rank(mine, b.berthType()))
                    .thenComparingInt(BerthCandidate::position)
                    .thenComparingInt(BerthCandidate::berthNo));
            for (BerthCandidate b : ordered) {
                pool.remove(b);
                if (allocations.tryInsert(newAllocation(ctx, b, SlotRange.FULL, held, pid)).isPresent()) {
                    return Optional.of(new Placed(b, SlotRange.FULL));
                }
            }
            // Pool exhausted (others took our candidates). Refetch once, wider.
            pool.addAll(allocations.candidates(ctx.runDate(), ctx.runId(), ctx.cls().code, ctx.range(),
                    SlotRange.FULL, mine, REFETCH_LIMIT));
        }
        return Optional.empty();
    }

    /**
     * RAC pairing: first try the free half of a berth whose other half is already taken over this range,
     * so RAC passengers share berths as intended instead of each spreading onto an empty one.
     */
    private Optional<Placed> placeRac(Ctx ctx, PassengerSpec p, long pid, boolean held) {
        List<BerthCandidate> freeA = allocations.candidates(ctx.runDate(), ctx.runId(), ctx.cls().code,
                ctx.range(), SlotRange.RAC_A, List.of(), 32);
        List<BerthCandidate> freeB = allocations.candidates(ctx.runDate(), ctx.runId(), ctx.cls().code,
                ctx.range(), SlotRange.RAC_B, List.of(), 32);
        java.util.Set<String> aKeys = new java.util.HashSet<>();
        freeA.forEach(b -> aKeys.add(b.runCoachId() + ":" + b.berthNo()));
        java.util.Set<String> bKeys = new java.util.HashSet<>();
        freeB.forEach(b -> bKeys.add(b.runCoachId() + ":" + b.berthNo()));

        List<Placed> order = new ArrayList<>();
        freeB.stream().filter(b -> !aKeys.contains(b.runCoachId() + ":" + b.berthNo()))
                .forEach(b -> order.add(new Placed(b, SlotRange.RAC_B)));   // A taken -> pair into B
        freeA.stream().filter(b -> !bKeys.contains(b.runCoachId() + ":" + b.berthNo()))
                .forEach(b -> order.add(new Placed(b, SlotRange.RAC_A)));   // B taken -> pair into A
        freeA.stream().filter(b -> bKeys.contains(b.runCoachId() + ":" + b.berthNo()))
                .forEach(b -> order.add(new Placed(b, SlotRange.RAC_A)));   // empty berth: open a new pair
        for (Placed candidate : order) {
            if (allocations.tryInsert(newAllocation(ctx, candidate.berth(), candidate.slot(), held, pid)).isPresent()) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private NewAllocation newAllocation(Ctx ctx, BerthCandidate b, SlotRange slot, boolean held, long pid) {
        return new NewAllocation(ctx.runDate(), ctx.runId(), b.runCoachId(), b.berthNo(), ctx.range(), slot,
                ctx.cls().code, ctx.quota().name(), held, holdMinutes, pid);
    }

    private List<PassengerSpec> byPickOrder(Plan plan, PassengerStatus status) {
        return new ArrayList<>(plan.passengers().stream().filter(p -> p.status() == status)
                .map(Planned::spec).sorted(prefs.pickOrder()).toList());
    }

    /**
     * Candidate pool for a group: one small fetch per distinct preferred type plus one unranked fetch.
     * A single fetch ranked by the union of preferences fills the LIMIT with the first type (36 LBs in
     * two 3A coaches) and starves everyone who asked for something else. Found in the Phase 5 smoke test.
     */
    private List<BerthCandidate> groupPool(Ctx ctx, List<PassengerSpec> group) {
        LinkedHashSet<String> types = new LinkedHashSet<>();
        group.forEach(p -> types.addAll(prefs.preferredTypes(p, ctx.cls())));
        int per = Math.max(group.size() * 3, 12);
        LinkedHashMap<String, BerthCandidate> merged = new LinkedHashMap<>();
        for (String type : types) {
            allocations.candidates(ctx.runDate(), ctx.runId(), ctx.cls().code, ctx.range(), SlotRange.FULL,
                    List.of(type), per).stream()
                    .filter(b -> b.berthType().equals(type))
                    .forEach(b -> merged.putIfAbsent(b.runCoachId() + ":" + b.berthNo(), b));
        }
        allocations.candidates(ctx.runDate(), ctx.runId(), ctx.cls().code, ctx.range(), SlotRange.FULL,
                List.of(), Math.max(group.size() * 4, 24))
                .forEach(b -> merged.putIfAbsent(b.runCoachId() + ":" + b.berthNo(), b));
        return new ArrayList<>(merged.values());
    }

    /** Coach with enough candidates to seat the whole group, preferring the earliest in the train. */
    private static Long familyCoach(List<BerthCandidate> pool, int groupSize) {
        if (groupSize < 2) {
            return null;
        }
        Map<Long, Integer> counts = new LinkedHashMap<>();
        pool.stream().sorted(Comparator.comparingInt(BerthCandidate::position))
                .forEach(b -> counts.merge(b.runCoachId(), 1, Integer::sum));
        return counts.entrySet().stream().filter(e -> e.getValue() >= groupSize)
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    private static int rank(List<String> preferred, String type) {
        int i = preferred.indexOf(type);
        return i < 0 ? 99 : i;
    }

    private static Assignment cnfAssignment(PassengerSpec p, long pid, BerthCandidate b) {
        return new Assignment(p, pid, PassengerStatus.CNF,
                "CNF/" + b.coachLabel() + "/" + b.berthNo() + "/" + b.berthType());
    }

    private static Assignment racAssignment(PassengerSpec p, long pid, Placed pl) {
        return new Assignment(p, pid, PassengerStatus.RAC,
                "RAC/" + pl.berth().coachLabel() + "/" + pl.berth().berthNo());
    }
}
