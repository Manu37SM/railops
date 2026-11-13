package in.railops.reservation.adapter.out.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Read-only, public facts about a run: what a passenger needs to pick stops and a class before booking (Phase 5). */
@Repository
public class RunDirectoryRepository {

    public record RunRow(long id, String trainNo, LocalDate runDate, String status, Instant bookingOpensAt) {
    }

    public record StopRow(int seq, String stationCode, Instant arrival, Instant departure, int distanceKm, boolean halt) {
    }

    private final JdbcClient jdbc;

    public RunDirectoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RunRow> byTrainAndDate(String trainNo, LocalDate date) {
        return jdbc.sql("SELECT id, train_no, run_date, status, booking_opens_at FROM reservation.train_run "
                        + "WHERE train_no = :t AND run_date = :d")
                .param("t", trainNo).param("d", date)
                .query((rs, n) -> row(rs)).optional();
    }

    public Optional<RunRow> byId(long id) {
        return jdbc.sql("SELECT id, train_no, run_date, status, booking_opens_at FROM reservation.train_run WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> row(rs)).optional();
    }

    public List<StopRow> stops(long runId) {
        return jdbc.sql("""
                        SELECT stop_seq, station_code, sched_arr, sched_dep, distance_km, is_halt
                        FROM reservation.run_stop WHERE train_run_id = :run ORDER BY stop_seq
                        """)
                .param("run", runId)
                .query((rs, n) -> new StopRow(rs.getInt("stop_seq"), rs.getString("station_code"),
                        instant(rs.getObject("sched_arr", OffsetDateTime.class)),
                        instant(rs.getObject("sched_dep", OffsetDateTime.class)),
                        rs.getInt("distance_km"), rs.getBoolean("is_halt")))
                .list();
    }

    /** Classes that have at least one active coach on the run. */
    public List<String> classes(long runId) {
        return jdbc.sql("SELECT DISTINCT class_code FROM reservation.run_coach WHERE train_run_id = :run AND status = 'ACTIVE' "
                        + "ORDER BY class_code")
                .param("run", runId).query(String.class).list();
    }

    private static RunRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RunRow(rs.getLong("id"), rs.getString("train_no"), rs.getObject("run_date", LocalDate.class),
                rs.getString("status"), rs.getObject("booking_opens_at", OffsetDateTime.class).toInstant());
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}
