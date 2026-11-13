package in.railops.reservation.adapter.out.persistence;

import in.railops.reservation.domain.model.SegmentRange;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.OptionalInt;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class WaitlistRepository {

    public record WaitingEntry(long id, long passengerId, int wlSeq, SegmentRange range) {
    }

    private final JdbcClient jdbc;

    public WaitlistRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Reserves {@code count} consecutive WL numbers, or empty if that would exceed the cap.
     * The WHERE clause makes "cap reached" a zero-row update instead of a CHECK violation,
     * which would abort the transaction.
     */
    public OptionalInt reserveSequence(long runId, String cls, String quota, int count) {
        return jdbc.sql("""
                        UPDATE reservation.waitlist_counter
                        SET next_seq = next_seq + :n
                        WHERE train_run_id = :run AND class_code = :cls AND quota_code = :quota
                          AND next_seq + :n - 1 <= wl_cap
                        RETURNING next_seq - :n AS first_seq
                        """)
                .param("n", count).param("run", runId).param("cls", cls).param("quota", quota)
                .query(Integer.class)
                .optional()
                .map(OptionalInt::of)
                .orElse(OptionalInt.empty());
    }

    public void insert(LocalDate runDate, long runId, String cls, String quota, int wlSeq,
                       long passengerId, SegmentRange range, String status) {
        jdbc.sql("""
                        INSERT INTO reservation.waitlist_entry
                          (run_date, train_run_id, class_code, quota_code, wl_seq, passenger_id, seg_range, status)
                        VALUES (:runDate, :run, :cls, :quota, :seq, :pid, CAST(:seg AS int4range), :status)
                        """)
                .param("runDate", runDate).param("run", runId).param("cls", cls).param("quota", quota)
                .param("seq", wlSeq).param("pid", passengerId).param("seg", range.toPg()).param("status", status)
                .update();
    }

    public int activatePending(LocalDate runDate, Collection<Long> passengerIds) {
        if (passengerIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        UPDATE reservation.waitlist_entry SET status = 'WAITING'
                        WHERE run_date = :runDate AND passenger_id IN (:ids) AND status = 'PENDING'
                        """)
                .param("runDate", runDate).param("ids", passengerIds)
                .update();
    }

    public int cancelOpen(LocalDate runDate, Collection<Long> passengerIds) {
        if (passengerIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        UPDATE reservation.waitlist_entry SET status = 'CANCELLED'
                        WHERE run_date = :runDate AND passenger_id IN (:ids) AND status IN ('PENDING','WAITING')
                        """)
                .param("runDate", runDate).param("ids", passengerIds)
                .update();
    }

    public List<WaitingEntry> waiting(LocalDate runDate, long runId, String cls, String quota, int limit) {
        return jdbc.sql("""
                        SELECT id, passenger_id, wl_seq, lower(seg_range) AS lo, upper(seg_range) AS hi
                        FROM reservation.waitlist_entry
                        WHERE run_date = :runDate AND train_run_id = :run AND class_code = :cls AND quota_code = :quota
                          AND status = 'WAITING'
                        ORDER BY wl_seq
                        LIMIT :limit
                        """)
                .param("runDate", runDate).param("run", runId).param("cls", cls).param("quota", quota)
                .param("limit", limit)
                .query((rs, n) -> new WaitingEntry(rs.getLong("id"), rs.getLong("passenger_id"), rs.getInt("wl_seq"),
                        new SegmentRange(rs.getInt("lo"), rs.getInt("hi"))))
                .list();
    }

    /** Bookings on a run that still have WAITING entries (chart time). */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<BookingRepository.BookingKey> bookingsWithWaiting(long runId, int limit) {
        return jdbc.sql("""
                        SELECT DISTINCT p.run_date, p.booking_id
                        FROM reservation.waitlist_entry w
                        JOIN reservation.passenger p ON p.run_date = w.run_date AND p.id = w.passenger_id
                        WHERE w.train_run_id = :run AND w.status = 'WAITING'
                        LIMIT :limit
                        """)
                .param("run", runId).param("limit", limit)
                .query((rs, n) -> new BookingRepository.BookingKey(rs.getObject(1, LocalDate.class), rs.getLong(2)))
                .list();
    }

    public int expireAtChart(LocalDate runDate, Collection<Long> passengerIds) {
        if (passengerIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        UPDATE reservation.waitlist_entry SET status = 'CHART_EXPIRED'
                        WHERE run_date = :runDate AND passenger_id IN (:ids) AND status = 'WAITING'
                        """)
                .param("runDate", runDate).param("ids", passengerIds)
                .update();
    }

    public void markPromoted(LocalDate runDate, long entryId, String status) {
        jdbc.sql("UPDATE reservation.waitlist_entry SET status = :s WHERE run_date = :runDate AND id = :id")
                .param("s", status).param("runDate", runDate).param("id", entryId)
                .update();
    }
}
