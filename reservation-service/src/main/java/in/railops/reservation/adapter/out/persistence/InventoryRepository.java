package in.railops.reservation.adapter.out.persistence;

import in.railops.reservation.domain.model.SegmentRange;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Quota counters. Every lock here is taken ORDER BY seg_idx; that ordering is what keeps
 * overlapping allocators from deadlocking (Phase 4, global lock order step 3).
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryRepository {

    public record SegmentRow(int segIdx, int cnfFree, int racFree) {
    }

    private static final String LOCK_SQL = """
            SELECT seg_idx, cnf_capacity - cnf_used AS cnf_free, rac_capacity - rac_used AS rac_free
            FROM reservation.segment_inventory
            WHERE train_run_id = :run AND class_code = :cls AND quota_code = :quota
            """;

    private final JdbcClient jdbc;

    public InventoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<SegmentRow> lockRange(long runId, String cls, String quota, SegmentRange range) {
        return jdbc.sql(LOCK_SQL + " AND seg_idx >= :from AND seg_idx < :to ORDER BY seg_idx FOR UPDATE")
                .param("run", runId).param("cls", cls).param("quota", quota)
                .param("from", range.from()).param("to", range.to())
                .query((rs, n) -> new SegmentRow(rs.getInt("seg_idx"), rs.getInt("cnf_free"), rs.getInt("rac_free")))
                .list();
    }

    /** Locks every segment of the pool. Used by promotion, which works across many ranges. */
    public List<SegmentRow> lockPool(long runId, String cls, String quota) {
        return jdbc.sql(LOCK_SQL + " ORDER BY seg_idx FOR UPDATE")
                .param("run", runId).param("cls", cls).param("quota", quota)
                .query((rs, n) -> new SegmentRow(rs.getInt("seg_idx"), rs.getInt("cnf_free"), rs.getInt("rac_free")))
                .list();
    }

    /**
     * Locks every counter row of a run, in (class, quota, seg) order. Taken by run cancellation and chart start
     * BEFORE they change the run status, so the status flip can't interleave with an allocation in flight:
     * an allocator either finishes first (and its booking is seen by the bulk job) or blocks here and then
     * re-reads the new status ({@link #runStatusUnderLock}). Allocators lock one pool, a subset of this order,
     * so no lock cycle is possible.
     */
    public int lockAllForRun(long runId) {
        return jdbc.sql("""
                        SELECT 1 FROM reservation.segment_inventory WHERE train_run_id = :run
                        ORDER BY class_code, quota_code, seg_idx FOR UPDATE
                        """).param("run", runId).query(Integer.class).list().size();
    }

    /** Fresh read (new statement snapshot) of the run status; call after acquiring counter locks. */
    public String runStatusUnderLock(long runId) {
        return jdbc.sql("SELECT status FROM reservation.train_run WHERE id = :run").param("run", runId)
                .query(String.class).single();
    }

    /** Caller must already hold the locks for {@code range}. CHECK constraints reject over/underflow. */
    public void adjust(long runId, String cls, String quota, SegmentRange range, int deltaCnf, int deltaRacSlots) {
        if (deltaCnf == 0 && deltaRacSlots == 0) {
            return;
        }
        int updated = jdbc.sql("""
                        UPDATE reservation.segment_inventory
                        SET cnf_used = cnf_used + :dc, rac_used = rac_used + :dr
                        WHERE train_run_id = :run AND class_code = :cls AND quota_code = :quota
                          AND seg_idx >= :from AND seg_idx < :to
                        """)
                .param("dc", deltaCnf).param("dr", deltaRacSlots)
                .param("run", runId).param("cls", cls).param("quota", quota)
                .param("from", range.from()).param("to", range.to())
                .update();
        if (updated != range.length()) {
            throw new IllegalStateException("inventory rows missing for " + runId + "/" + cls + "/" + quota + " " + range);
        }
    }
}
