package in.railops.reservation.adapter.out.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/** Reads the observable pool state the WL model needs, and owns the prediction log. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class PredictionRepository {

    public record PoolState(String trainNo, LocalDate runDate, String runStatus, int capacity, int waiting,
                            int bookingsLast7, int userCancels) {
    }

    /** The waitlisted part of a booking: how many passengers still wait, and the queue position of the last one. */
    public record PartyQueue(int waitingPassengers, int lastPosition) {
    }

    public record Calibration(long n, double brier, double meanPredicted, double observedRate) {
    }

    private final JdbcClient jdbc;

    public PredictionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Pool-level state. Capacity is the widest segment's CNF capacity: the simulation the model was trained on has
     * single-segment pools, so for partial-route journeys this is an approximation (documented in the model card).
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Optional<PoolState> poolState(long runId, String cls, String quota, Instant now) {
        return jdbc.sql("""
                        SELECT r.train_no, r.run_date, r.status,
                          (SELECT COALESCE(max(s.cnf_capacity), 0) FROM reservation.segment_inventory s
                            WHERE s.train_run_id = r.id AND s.class_code = :cls AND s.quota_code = :quota) cap,
                          (SELECT count(*) FROM reservation.waitlist_entry w
                            WHERE w.run_date = r.run_date AND w.train_run_id = r.id AND w.class_code = :cls
                              AND w.quota_code = :quota AND w.status IN ('PENDING','WAITING')) waiting,
                          (SELECT count(*) FROM reservation.booking b
                            WHERE b.run_date = r.run_date AND b.train_run_id = r.id AND b.class_code = :cls
                              AND b.quota_code = :quota AND b.created_at >= :since) recent,
                          (SELECT count(*) FROM reservation.cancellation c
                             JOIN reservation.booking b ON b.run_date = c.run_date AND b.id = c.booking_id
                            WHERE c.run_date = r.run_date AND b.train_run_id = r.id AND b.class_code = :cls
                              AND b.quota_code = :quota AND c.kind = 'USER') cancels
                        FROM reservation.train_run r WHERE r.id = :run
                        """)
                .param("run", runId).param("cls", cls).param("quota", quota)
                .param("since", java.sql.Timestamp.from(now.minus(java.time.Duration.ofDays(7))))
                .query((rs, n) -> new PoolState(rs.getString(1), rs.getObject(2, LocalDate.class), rs.getString(3),
                        rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7)))
                .optional();
    }

    /**
     * Current position = PENDING/WAITING entries in the pool at or ahead of this party's last waiting member. That is
     * the "current status WL n" a passenger sees, which is what the training feature measured at booking time.
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Optional<PartyQueue> partyQueue(LocalDate runDate, long bookingId) {
        return jdbc.sql("""
                        WITH mine AS (
                          SELECT w.train_run_id, w.class_code, w.quota_code, w.wl_seq
                          FROM reservation.waitlist_entry w
                          JOIN reservation.passenger p ON p.run_date = w.run_date AND p.id = w.passenger_id
                          WHERE w.run_date = :d AND p.booking_id = :b AND w.status IN ('PENDING','WAITING'))
                        SELECT (SELECT count(*) FROM mine),
                               (SELECT count(*) FROM reservation.waitlist_entry o, (SELECT * FROM mine
                                  ORDER BY wl_seq DESC LIMIT 1) last
                                 WHERE o.run_date = :d AND o.train_run_id = last.train_run_id
                                   AND o.class_code = last.class_code AND o.quota_code = last.quota_code
                                   AND o.status IN ('PENDING','WAITING') AND o.wl_seq <= last.wl_seq)
                        """)
                .param("d", runDate).param("b", bookingId)
                .query((rs, n) -> new PartyQueue(rs.getInt(1), rs.getInt(2)))
                .optional()
                .filter(q -> q.waitingPassengers() > 0);
    }

    /** Idempotent per (booking, model, 6-hour bucket). Returns false when this bucket was already logged. */
    public boolean log(String modelVersion, Instant at, long runId, LocalDate runDate, long bookingId, String cls,
                       String quota, String featuresJson, double probability) {
        return jdbc.sql("""
                        INSERT INTO reservation.wl_prediction (model_version, predicted_at, log_bucket, train_run_id,
                          run_date, booking_id, class_code, quota_code, features, probability)
                        VALUES (:v, :at, :bucket, :run, :d, :b, :cls, :quota, CAST(:f AS jsonb), :p)
                        ON CONFLICT DO NOTHING
                        """)
                .param("v", modelVersion).param("at", java.sql.Timestamp.from(at))
                .param("bucket", at.getEpochSecond() / 21_600).param("run", runId).param("d", runDate)
                .param("b", bookingId).param("cls", cls).param("quota", quota).param("f", featuresJson)
                .param("p", probability)
                .update() == 1;
    }

    /**
     * Resolves predictions for runs whose chart is prepared. A party counts as CONFIRMED when every passenger that was
     * waitlisted ended PROMOTED_CNF or PROMOTED_RAC (an RAC passenger travels), NOT_CONFIRMED if any ended
     * CHART_EXPIRED, VOID if the passenger cancelled or the run was cancelled. Bounded per call.
     */
    public int resolveCharted(Instant now, int limit) {
        return jdbc.sql("""
                        WITH due AS (
                          SELECT p.id, p.run_date, p.booking_id, r.status run_status
                          FROM reservation.wl_prediction p
                          JOIN reservation.train_run r ON r.id = p.train_run_id
                          WHERE p.outcome IS NULL AND r.status <> 'SCHEDULED'
                          ORDER BY p.id LIMIT :limit
                          FOR UPDATE OF p SKIP LOCKED),
                        party AS (
                          SELECT d.id,
                            count(*) FILTER (WHERE w.status = 'CHART_EXPIRED') expired,
                            count(*) FILTER (WHERE w.status IN ('PENDING','WAITING')) open,
                            count(*) FILTER (WHERE w.status IN ('PROMOTED_CNF','PROMOTED_RAC')) promoted,
                            count(w.id) FILTER (WHERE w.status <> 'CANCELLED') total
                          FROM due d
                          LEFT JOIN reservation.passenger x ON x.run_date = d.run_date AND x.booking_id = d.booking_id
                          LEFT JOIN reservation.waitlist_entry w ON w.run_date = x.run_date AND w.passenger_id = x.id
                          GROUP BY d.id)
                        UPDATE reservation.wl_prediction p
                        SET outcome = CASE
                              WHEN d.run_status = 'CANCELLED' THEN 'VOID'
                              WHEN party.expired > 0 THEN 'NOT_CONFIRMED'
                              WHEN party.promoted > 0 AND party.promoted + party.expired = party.total THEN 'CONFIRMED'
                              ELSE 'VOID' END,
                            resolved_at = :now
                        FROM due d JOIN party ON party.id = d.id
                        WHERE p.id = d.id AND (d.run_status = 'CANCELLED' OR party.open = 0)
                        """)
                .param("limit", limit).param("now", java.sql.Timestamp.from(now))
                .update();
    }

    /**
     * Calibration over resolved predictions since {@code since}. Only the FIRST logged prediction per booking counts:
     * passengers who refresh often would otherwise dominate, and the first number is the one that informs the
     * decision to book a WL ticket at all.
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Calibration calibration(String modelVersion, Instant since) {
        return jdbc.sql("""
                        WITH firsts AS (
                          SELECT DISTINCT ON (run_date, booking_id) probability,
                                 CASE outcome WHEN 'CONFIRMED' THEN 1.0 ELSE 0.0 END y
                          FROM reservation.wl_prediction
                          WHERE model_version = :v AND outcome IN ('CONFIRMED','NOT_CONFIRMED') AND resolved_at >= :since
                          ORDER BY run_date, booking_id, predicted_at)
                        SELECT count(*), COALESCE(avg((probability - y) ^ 2), 0), COALESCE(avg(probability), 0),
                               COALESCE(avg(y), 0)
                        FROM firsts
                        """)
                .param("v", modelVersion).param("since", java.sql.Timestamp.from(since))
                .query((rs, n) -> new Calibration(rs.getLong(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4)))
                .single();
    }

    public record RunPredictionStats(long logged, long resolved, Double brier) {
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public RunPredictionStats runStats(long runId) {
        return jdbc.sql("""
                        SELECT count(*), count(*) FILTER (WHERE outcome IN ('CONFIRMED','NOT_CONFIRMED')),
                          avg((probability - CASE outcome WHEN 'CONFIRMED' THEN 1.0 ELSE 0.0 END) ^ 2)
                            FILTER (WHERE outcome IN ('CONFIRMED','NOT_CONFIRMED'))
                        FROM reservation.wl_prediction WHERE train_run_id = :run
                        """)
                .param("run", runId)
                .query((rs, n) -> new RunPredictionStats(rs.getLong(1), rs.getLong(2),
                        rs.getObject(3) == null ? null : rs.getDouble(3)))
                .single();
    }

}
