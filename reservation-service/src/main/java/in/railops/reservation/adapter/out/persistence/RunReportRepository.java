package in.railops.reservation.adapter.out.persistence;

import in.railops.reservation.application.StaffReports.MoneySummary;
import in.railops.reservation.application.StaffReports.PoolFunnel;
import in.railops.reservation.application.StaffReports.PoolOccupancy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-only staff reports for one run (Phase 15: moved out of StaffRunController, SQL unchanged). Each query is
 * bounded by the run's pools. MANDATORY: callers audit the read in the same transaction.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
public class RunReportRepository {

    private final JdbcClient jdbc;

    public RunReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<PoolOccupancy> occupancy(long runId) {
        return jdbc.sql("""
                        SELECT s.class_code, s.quota_code, max(s.cnf_capacity) cnf_cap, max(s.cnf_used) cnf_used,
                               max(s.rac_capacity) rac_cap, max(s.rac_used) rac_used,
                               COALESCE(max(w.next_seq) - 1, 0) wl_issued
                        FROM reservation.segment_inventory s
                        LEFT JOIN reservation.waitlist_counter w ON w.train_run_id = s.train_run_id
                             AND w.class_code = s.class_code AND w.quota_code = s.quota_code
                        WHERE s.train_run_id = :run
                        GROUP BY s.class_code, s.quota_code ORDER BY 1, 2
                        """)
                .param("run", runId)
                .query((rs, n) -> new PoolOccupancy(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4),
                        rs.getInt(5), rs.getInt(6), rs.getInt(7)))
                .list();
    }

    public List<PoolFunnel> funnel(long runId) {
        return jdbc.sql("""
                        WITH inv AS (
                          SELECT class_code, quota_code, max(cnf_capacity) cap, max(cnf_used) used
                          FROM reservation.segment_inventory WHERE train_run_id = :run GROUP BY 1, 2),
                        wl AS (
                          SELECT class_code, quota_code,
                            count(*) issued,
                            count(*) FILTER (WHERE status IN ('PROMOTED_CNF','PROMOTED_RAC')) promoted,
                            count(*) FILTER (WHERE status = 'CHART_EXPIRED') expired,
                            count(*) FILTER (WHERE status = 'CANCELLED') cancelled,
                            count(*) FILTER (WHERE status IN ('PENDING','WAITING')) open
                          FROM reservation.waitlist_entry
                          WHERE train_run_id = :run
                            AND run_date = (SELECT run_date FROM reservation.train_run WHERE id = :run)
                          GROUP BY 1, 2)
                        SELECT i.class_code, i.quota_code, i.cap, i.used,
                               COALESCE(w.issued, 0), COALESCE(w.promoted, 0), COALESCE(w.expired, 0),
                               COALESCE(w.cancelled, 0), COALESCE(w.open, 0)
                        FROM inv i LEFT JOIN wl w USING (class_code, quota_code) ORDER BY 1, 2
                        """)
                .param("run", runId)
                .query((rs, n) -> new PoolFunnel(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4),
                        rs.getInt(3) == 0 ? 0.0 : Math.round(1000.0 * rs.getInt(4) / rs.getInt(3)) / 1000.0,
                        rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9)))
                .list();
    }

    /** MONEY, indicative: reservation's own rows. The payment ledger is the system of record. */
    public MoneySummary money(long runId) {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE b.status NOT IN ('PENDING_PAYMENT','EXPIRED')),
                               COALESCE(sum(b.total_fare) FILTER (WHERE b.status NOT IN ('PENDING_PAYMENT','EXPIRED')), 0),
                               (SELECT count(*) FROM reservation.cancellation c JOIN reservation.booking x
                                  ON x.run_date = c.run_date AND x.id = c.booking_id
                                 WHERE x.train_run_id = :run AND c.kind = 'USER'),
                               (SELECT COALESCE(sum(c.refund_amount), 0) FROM reservation.cancellation c
                                  JOIN reservation.booking x ON x.run_date = c.run_date AND x.id = c.booking_id
                                 WHERE x.train_run_id = :run)
                        FROM reservation.booking b
                        WHERE b.train_run_id = :run
                          AND b.run_date = (SELECT run_date FROM reservation.train_run WHERE id = :run)
                        """)
                .param("run", runId)
                .query((rs, n) -> new MoneySummary(rs.getLong(1), rs.getBigDecimal(2), rs.getLong(3), rs.getBigDecimal(4)))
                .single();
    }
}
