package in.railops.reservation.adapter.out.persistence;

import in.railops.reservation.domain.model.SegmentRange;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Phase 6: which berths of a class are taken over a journey, for the passenger-facing seat map. Same rule as the
 * allocation engine: any allocation (held or confirmed) overlapping the journey's segments takes the berth, or half
 * of it for RAC. Returns berth facts only, never who holds them.
 */
@Repository
public class SeatMapRepository {

    /** taken: 0 free, 1 half taken (RAC share), 2 fully taken. */
    public record BerthRow(String coachLabel, int position, int berthNo, String berthType, int taken) {
    }

    private final JdbcClient jdbc;

    public SeatMapRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<BerthRow> berths(LocalDate runDate, long runId, String cls, SegmentRange range) {
        return jdbc.sql("""
                        SELECT c.coach_label, c.position, bl.berth_no, bl.berth_type,
                               CASE WHEN bool_or(a.slot_range = '[1,3)'::int4range) OR count(a.id) >= 2 THEN 2
                                    WHEN count(a.id) = 1 THEN 1 ELSE 0 END AS taken
                        FROM reservation.run_coach c
                        JOIN reservation.berth_layout bl ON bl.coach_type = c.coach_type
                        LEFT JOIN reservation.allocation a
                               ON a.run_date = :runDate AND a.run_coach_id = c.id AND a.berth_no = bl.berth_no
                              AND a.seg_range && CAST(:seg AS int4range)
                        WHERE c.train_run_id = :run AND c.class_code = :cls AND c.status = 'ACTIVE'
                        GROUP BY c.coach_label, c.position, bl.berth_no, bl.berth_type
                        ORDER BY c.position, bl.berth_no
                        """)
                .param("runDate", runDate).param("run", runId).param("cls", cls).param("seg", range.toPg())
                .query((rs, n) -> new BerthRow(rs.getString("coach_label"), rs.getInt("position"), rs.getInt("berth_no"),
                        rs.getString("berth_type"), rs.getInt("taken")))
                .list();
    }
}
