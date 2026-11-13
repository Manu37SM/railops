package in.railops.reservation.adapter.out.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class RunRepository {

    /** Booking-time view of a run. Time comparisons are evaluated by the database clock. */
    public record RunView(long id, String trainNo, LocalDate runDate, String status,
                          boolean generalOpen, boolean tatkalOpen) {
    }

    public record StopView(int stopSeq, String stationCode, int distanceKm, boolean halt, boolean departed) {
    }

    private final JdbcClient jdbc;

    public RunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RunView> find(long runId) {
        return jdbc.sql("""
                        SELECT id, train_no, run_date, status,
                               booking_opens_at <= now() AS general_open,
                               tatkal_opens_at  <= now() AS tatkal_open
                        FROM reservation.train_run WHERE id = :id
                        """)
                .param("id", runId)
                .query((rs, n) -> new RunView(rs.getLong("id"), rs.getString("train_no"),
                        rs.getObject("run_date", LocalDate.class), rs.getString("status"),
                        rs.getBoolean("general_open"), rs.getBoolean("tatkal_open")))
                .optional();
    }

    /** Minutes from the DB clock to the scheduled departure at {@code stopSeq}; negative once departed. */
    public long minutesToDeparture(long runId, int stopSeq) {
        return jdbc.sql("""
                        SELECT floor(EXTRACT(EPOCH FROM (sched_dep - now())) / 60)::bigint
                        FROM reservation.run_stop WHERE train_run_id = :run AND stop_seq = :seq
                        """)
                .param("run", runId).param("seq", stopSeq)
                .query(Long.class).optional().orElse(Long.MIN_VALUE);
    }

    /** Boarding and alighting stops; {@code departed} is true when the scheduled departure has passed. */
    public List<StopView> journeyStops(long runId, int fromSeq, int toSeq) {
        return jdbc.sql("""
                        SELECT stop_seq, station_code, distance_km, is_halt,
                               COALESCE(sched_dep <= now(), true) AS departed
                        FROM reservation.run_stop
                        WHERE train_run_id = :run AND stop_seq IN (:from, :to)
                        ORDER BY stop_seq
                        """)
                .param("run", runId).param("from", fromSeq).param("to", toSeq)
                .query((rs, n) -> new StopView(rs.getInt("stop_seq"), rs.getString("station_code"),
                        rs.getInt("distance_km"), rs.getBoolean("is_halt"), rs.getBoolean("departed")))
                .list();
    }
}
