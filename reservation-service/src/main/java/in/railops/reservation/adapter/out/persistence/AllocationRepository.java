package in.railops.reservation.adapter.out.persistence;

import in.railops.reservation.domain.model.SegmentRange;
import in.railops.reservation.domain.model.SlotRange;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class AllocationRepository {

    public record BerthCandidate(long runCoachId, String coachLabel, int position, int berthNo, String berthType) {
    }

    public record NewAllocation(LocalDate runDate, long runId, long runCoachId, int berthNo, SegmentRange range,
                                SlotRange slot, String cls, String quota, boolean held, int holdMinutes,
                                long passengerId) {
    }

    public record Released(long passengerId, SlotRange slot, SegmentRange range) {
    }

    public record LiveAllocation(long id, long passengerId, long runCoachId, int berthNo, SlotRange slot, SegmentRange range) {
    }

    private final JdbcClient jdbc;

    public AllocationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Free berths for a range and slot. Up to three preferred berth types are ranked first via CASE;
     * a NULL preference never matches. Ordering then follows coach position, so groups fill coaches
     * front to back and stay together.
     */
    public List<BerthCandidate> candidates(LocalDate runDate, long runId, String cls, SegmentRange range,
                                           SlotRange slot, List<String> preferredTypes, int limit) {
        String p1 = preferredTypes.size() > 0 ? preferredTypes.get(0) : null;
        String p2 = preferredTypes.size() > 1 ? preferredTypes.get(1) : null;
        String p3 = preferredTypes.size() > 2 ? preferredTypes.get(2) : null;
        return jdbc.sql("""
                        SELECT c.id AS run_coach_id, c.coach_label, c.position, bl.berth_no, bl.berth_type
                        FROM reservation.run_coach c
                        JOIN reservation.berth_layout bl ON bl.coach_type = c.coach_type
                        WHERE c.train_run_id = :run AND c.class_code = :cls AND c.status = 'ACTIVE'
                          AND bl.rac_eligible = :rac
                          AND NOT EXISTS (
                                SELECT 1 FROM reservation.allocation a
                                WHERE a.run_date = :runDate AND a.run_coach_id = c.id AND a.berth_no = bl.berth_no
                                  AND a.seg_range && CAST(:seg AS int4range)
                                  AND a.slot_range && CAST(:slot AS int4range))
                        ORDER BY CASE bl.berth_type WHEN :p1 THEN 0 WHEN :p2 THEN 1 WHEN :p3 THEN 2 ELSE 3 END,
                                 -- best fit: prefer berths whose existing bookings end where this one starts
                                 -- (or start where it ends). Packing ranges end-to-end limits fragmentation.
                                 CASE WHEN EXISTS (
                                        SELECT 1 FROM reservation.allocation a2
                                        WHERE a2.run_date = :runDate AND a2.run_coach_id = c.id AND a2.berth_no = bl.berth_no
                                          AND (upper(a2.seg_range) = :from OR lower(a2.seg_range) = :to))
                                      THEN 0 ELSE 1 END,
                                 c.position, bl.berth_no
                        LIMIT :limit
                        """)
                .param("run", runId).param("cls", cls).param("rac", slot.isRac())
                .param("from", range.from()).param("to", range.to())
                .param("runDate", runDate).param("seg", range.toPg()).param("slot", slot.pg)
                .param("p1", p1).param("p2", p2).param("p3", p3).param("limit", limit)
                .query((rs, n) -> new BerthCandidate(rs.getLong("run_coach_id"), rs.getString("coach_label"),
                        rs.getInt("position"), rs.getInt("berth_no"), rs.getString("berth_type")))
                .list();
    }

    /**
     * Attempts the insert. Empty result means the exclusion constraint rejected it (someone took the
     * berth between candidate query and insert). ON CONFLICT DO NOTHING keeps the transaction alive;
     * a raised 23P01 would abort it.
     */
    public Optional<Long> tryInsert(NewAllocation a) {
        return jdbc.sql("""
                        INSERT INTO reservation.allocation
                          (run_date, train_run_id, run_coach_id, berth_no, seg_range, slot_range,
                           class_code, quota_code, state, hold_expires_at, passenger_id)
                        VALUES (:runDate, :run, :coach, :berth, CAST(:seg AS int4range), CAST(:slot AS int4range),
                                :cls, :quota, :state,
                                CASE WHEN :held THEN now() + make_interval(mins => :holdMins) END,
                                :pid)
                        ON CONFLICT DO NOTHING
                        RETURNING id
                        """)
                .param("runDate", a.runDate()).param("run", a.runId()).param("coach", a.runCoachId())
                .param("berth", a.berthNo()).param("seg", a.range().toPg()).param("slot", a.slot().pg)
                .param("cls", a.cls()).param("quota", a.quota()).param("state", a.held() ? "HELD" : "CONFIRMED")
                .param("held", a.held()).param("holdMins", a.holdMinutes()).param("pid", a.passengerId())
                .query(Long.class)
                .optional();
    }

    public int confirmHeld(LocalDate runDate, Collection<Long> passengerIds) {
        if (passengerIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        UPDATE reservation.allocation SET state = 'CONFIRMED', hold_expires_at = NULL
                        WHERE run_date = :runDate AND passenger_id IN (:ids) AND state = 'HELD'
                        """)
                .param("runDate", runDate).param("ids", passengerIds)
                .update();
    }

    /** Moves live allocations to history and reports what was freed, so counters can be decremented. */
    public List<Released> releaseForPassengers(LocalDate runDate, Collection<Long> passengerIds, String reason) {
        if (passengerIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        WITH moved AS (
                          DELETE FROM reservation.allocation
                          WHERE run_date = :runDate AND passenger_id IN (:ids)
                          RETURNING *)
                        INSERT INTO reservation.allocation_history
                        SELECT moved.*, now(), :reason FROM moved
                        RETURNING passenger_id, slot_range::text AS slot, lower(seg_range) AS lo, upper(seg_range) AS hi
                        """)
                .param("runDate", runDate).param("ids", passengerIds).param("reason", reason)
                .query((rs, n) -> new Released(rs.getLong("passenger_id"), SlotRange.fromPg(rs.getString("slot")),
                        new SegmentRange(rs.getInt("lo"), rs.getInt("hi"))))
                .list();
    }

    public void releaseById(LocalDate runDate, long allocationId, String reason) {
        int moved = jdbc.sql("""
                        WITH moved AS (
                          DELETE FROM reservation.allocation WHERE run_date = :runDate AND id = :id RETURNING *)
                        INSERT INTO reservation.allocation_history SELECT moved.*, now(), :reason FROM moved
                        """)
                .param("runDate", runDate).param("id", allocationId).param("reason", reason)
                .update();
        if (moved != 1) {
            throw new IllegalStateException("allocation " + allocationId + " not found for release");
        }
    }

    /** Confirmed RAC allocations in a pool, oldest first: RAC → CNF promotion order. */
    public List<LiveAllocation> confirmedRacInPool(LocalDate runDate, long runId, String cls, String quota) {
        return jdbc.sql("""
                        SELECT id, passenger_id, run_coach_id, berth_no, slot_range::text AS slot,
                               lower(seg_range) AS lo, upper(seg_range) AS hi
                        FROM reservation.allocation
                        WHERE run_date = :runDate AND train_run_id = :run AND class_code = :cls AND quota_code = :quota
                          AND state = 'CONFIRMED' AND slot_range <> '[1,3)'::int4range
                        ORDER BY id
                        """)
                .param("runDate", runDate).param("run", runId).param("cls", cls).param("quota", quota)
                .query((rs, n) -> new LiveAllocation(rs.getLong("id"), rs.getLong("passenger_id"),
                        rs.getLong("run_coach_id"), rs.getInt("berth_no"), SlotRange.fromPg(rs.getString("slot")),
                        new SegmentRange(rs.getInt("lo"), rs.getInt("hi"))))
                .list();
    }
}
