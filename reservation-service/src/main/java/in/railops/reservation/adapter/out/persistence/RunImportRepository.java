package in.railops.reservation.adapter.out.persistence;

import in.railops.reservation.domain.policy.RunPlanner.Layout;
import in.railops.reservation.domain.policy.RunPlanner.PlannedCoach;
import in.railops.reservation.domain.policy.RunPlanner.PlannedPool;
import in.railops.reservation.domain.policy.RunPlanner.PlannedStop;
import in.railops.reservation.domain.policy.RunPlanner.RunPlan;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Writes an imported run (Phase 3B). Callers own the transaction: one per run date. */
@Repository
public class RunImportRepository {

    private final JdbcClient jdbc;

    public RunImportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Berths and RAC (side-lower) berths per coach type, from the reference layouts. */
    public Map<String, Layout> layouts(Collection<String> coachTypes) {
        Map<String, Layout> out = new HashMap<>();
        if (coachTypes.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT coach_type, count(*) AS berths, count(*) FILTER (WHERE rac_eligible) AS rac
                        FROM reservation.berth_layout WHERE coach_type IN (:types) GROUP BY coach_type
                        """)
                .param("types", coachTypes)
                .query(rs -> {
                    out.put(rs.getString("coach_type"), new Layout(rs.getInt("berths"), rs.getInt("rac")));
                });
        return out;
    }

    public Optional<Long> existingRun(String trainNo, java.time.LocalDate runDate) {
        return jdbc.sql("SELECT id FROM reservation.train_run WHERE train_no = :t AND run_date = :d")
                .param("t", trainNo).param("d", runDate).query(Long.class).optional();
    }

    /**
     * Inserts the run and everything under it. Returns the new id, or empty if the (train, date) run already exists:
     * an existing run is never changed here, because it may already carry bookings.
     */
    public Optional<Long> insert(RunPlan plan) {
        Optional<Long> id = jdbc.sql("""
                        INSERT INTO reservation.train_run
                          (train_no, run_date, status, booking_opens_at, tatkal_opens_at, chart1_due_at, chart2_due_at)
                        VALUES (:train, :date, 'SCHEDULED', :opens, :tatkal, :chart1, :chart2)
                        ON CONFLICT (train_no, run_date) DO NOTHING
                        RETURNING id
                        """)
                .param("train", plan.trainNo()).param("date", plan.runDate())
                .param("opens", plan.bookingOpensAt()).param("tatkal", plan.tatkalOpensAt())
                .param("chart1", plan.chart1DueAt()).param("chart2", plan.chart2DueAt())
                .query(Long.class).optional();
        if (id.isEmpty()) {
            return id;
        }
        long run = id.get();
        for (PlannedStop s : plan.stops()) {
            jdbc.sql("""
                            INSERT INTO reservation.run_stop (train_run_id, stop_seq, station_code, sched_arr, sched_dep, distance_km)
                            VALUES (:run, :seq, :code, :arr, :dep, :km)
                            """)
                    .param("run", run).param("seq", s.seq()).param("code", s.stationCode())
                    .param("arr", s.arrival()).param("dep", s.departure()).param("km", s.distanceKm())
                    .update();
        }
        for (PlannedCoach c : plan.coaches()) {
            jdbc.sql("""
                            INSERT INTO reservation.run_coach (train_run_id, coach_label, coach_type, class_code, position)
                            VALUES (:run, :label, :type, :cls, :pos)
                            """)
                    .param("run", run).param("label", c.label()).param("type", c.coachType())
                    .param("cls", c.classCode()).param("pos", c.position())
                    .update();
        }
        for (PlannedPool p : plan.pools()) {
            jdbc.sql("""
                            INSERT INTO reservation.segment_inventory (train_run_id, class_code, quota_code, seg_idx, cnf_capacity, rac_capacity)
                            SELECT :run, :cls, :quota, s, :cnf, :rac FROM generate_series(0, :lastSeg) AS s
                            """)
                    .param("run", run).param("cls", p.classCode()).param("quota", p.quota().name())
                    .param("cnf", p.cnfCapacity()).param("rac", p.racCapacity()).param("lastSeg", plan.segments() - 1)
                    .update();
            jdbc.sql("""
                            INSERT INTO reservation.waitlist_counter (train_run_id, class_code, quota_code, wl_cap)
                            VALUES (:run, :cls, :quota, :cap)
                            """)
                    .param("run", run).param("cls", p.classCode()).param("quota", p.quota().name()).param("cap", p.waitlistCap())
                    .update();
        }
        return id;
    }
}
