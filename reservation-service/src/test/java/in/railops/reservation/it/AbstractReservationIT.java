package in.railops.reservation.it;

import in.railops.reservation.application.WaitingRoomService;
import in.railops.testsupport.EventContracts;
import in.railops.testsupport.ItPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real reservation-service context (Flyway, JPA/JDBC, transactions, security config) on a throwaway PostgreSQL
 * database. Off: timers (jobs are called explicitly), Kafka listeners and topic creation, the Redis waiting room.
 * The outbox is inspected directly: what would be published is exactly what is in it.
 */
@SpringBootTest
@ActiveProfiles("local")
abstract class AbstractReservationIT {

    static final ItPostgres.Db DB = ItPostgres.newDatabase();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::url);
        r.add("spring.datasource.username", DB::user);
        r.add("spring.datasource.password", DB::password);
        r.add("railops.scheduling.enabled", () -> "false");
        r.add("spring.kafka.listener.auto-startup", () -> "false");
        r.add("spring.kafka.admin.auto-create", () -> "false");
        r.add("spring.kafka.bootstrap-servers", () -> "127.0.0.1:1");   // never reached: nothing polls the outbox
        r.add("railops.availability.source", () -> "db");
    }

    @MockitoBean
    WaitingRoomService waitingRoom;   // Redis-backed; covered by edge_e2e. No-op here = no room open.

    @Autowired
    JdbcClient jdbc;
    @Autowired
    DataSource dataSource;

    @BeforeEach
    void freshTrain() {
        jdbc.sql("""
                TRUNCATE reservation.wl_prediction, reservation.allocation, reservation.booking, reservation.passenger, reservation.waitlist_entry,
                         reservation.pnr_directory, reservation.idempotency_key, reservation.outbox,
                         reservation.allocation_history, reservation.cancellation, reservation.bulk_job,
                         reservation.processed_event, reservation.segment_inventory, reservation.waitlist_counter,
                         reservation.run_coach, reservation.run_stop, reservation.chart, reservation.train_run CASCADE
                """).update();
        Path seed = Path.of("..", "scripts", "seed.sql").toAbsolutePath().normalize();
        new ResourceDatabasePopulator(new FileSystemResource(seed)).execute(dataSource);
    }

    // ---------------------------------------------------------------- invariants (same as scripts/invariants.sql)

    void assertInventoryInvariants() {
        Integer doubleBooked = jdbc.sql("""
                SELECT count(*) FROM reservation.allocation a JOIN reservation.allocation b
                  ON a.run_coach_id=b.run_coach_id AND a.berth_no=b.berth_no AND a.id<b.id
                 AND a.seg_range && b.seg_range AND a.slot_range && b.slot_range""").query(Integer.class).single();
        assertThat(doubleBooked).as("I1 double-booked berth halves").isZero();
        List<Map<String, Object>> drift = jdbc.sql("""
                WITH actual AS (
                  SELECT s.quota_code, s.seg_idx, s.cnf_used, s.rac_used,
                         count(a.id) FILTER (WHERE a.slot_range='[1,3)') AS full_alloc,
                         count(a.id) FILTER (WHERE a.slot_range<>'[1,3)') AS rac_alloc
                  FROM reservation.segment_inventory s
                  LEFT JOIN reservation.allocation a ON a.train_run_id=s.train_run_id AND a.class_code=s.class_code
                       AND a.quota_code=s.quota_code AND a.seg_range @> s.seg_idx::int
                  GROUP BY 1,2,3,4)
                SELECT * FROM actual WHERE cnf_used<>full_alloc OR rac_used<>rac_alloc""").query().listOfRows();
        assertThat(drift).as("I2 counters vs allocations").isEmpty();
        Integer overCapacity = jdbc.sql("""
                SELECT count(*) FROM reservation.segment_inventory
                WHERE cnf_used > cnf_capacity OR rac_used > rac_capacity OR cnf_used < 0 OR rac_used < 0""")
                .query(Integer.class).single();
        assertThat(overCapacity).as("I3 counters within [0, capacity]").isZero();
        Integer dupWl = jdbc.sql("""
                SELECT count(*) FROM (SELECT train_run_id, class_code, quota_code, wl_seq FROM reservation.waitlist_entry
                                      GROUP BY 1,2,3,4 HAVING count(*) > 1) d""").query(Integer.class).single();
        assertThat(dupWl).as("I4 duplicate WL numbers").isZero();
    }

    /** Every event the transactions wrote to the outbox honours its published contract. */
    void assertOutboxHonoursContracts() {
        var rows = jdbc.sql("SELECT event_type, payload::text FROM reservation.outbox ORDER BY id")
                .query((rs, i) -> new String[]{rs.getString(1), rs.getString(2)}).list();
        assertThat(rows).as("a test that checks contracts must have produced events").isNotEmpty();
        for (String[] r : rows) {
            if (r[0].equals("StaffAction")) {
                continue;   // audit stream: consumed by identity's audit log, contract owned there
            }
            assertThat(EventContracts.validate(r[0], 1, r[1])).as(r[0] + " " + r[1]).isEmpty();
        }
    }
}
