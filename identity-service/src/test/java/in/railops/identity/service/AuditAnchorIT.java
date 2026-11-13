package in.railops.identity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import in.railops.identity.domain.Repositories.AuditEntryRepository;
import in.railops.testsupport.ItPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AUTH/audit: the attack the hash chain alone cannot catch. A database superuser rewrites history AND recomputes
 * every later hash: verify() is fooled; the anchors published earlier to WORM storage are not.
 */
@SpringBootTest
@ActiveProfiles("local")
class AuditAnchorIT {

    static final ItPostgres.Db DB = ItPostgres.newDatabase();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::url);
        r.add("spring.datasource.username", DB::user);
        r.add("spring.datasource.password", DB::password);
        r.add("railops.audit.consume", () -> "false");
        r.add("railops.scheduling.enabled", () -> "false");
        r.add("spring.kafka.admin.auto-create", () -> "false");
        r.add("spring.kafka.bootstrap-servers", () -> "127.0.0.1:1");
    }

    @Autowired AuditService audit;
    @Autowired AuditEntryRepository repo;
    @Autowired JdbcClient jdbc;
    final ObjectMapper canonical = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    void record(int n, String tag) {
        for (int i = 0; i < n; i++) {
            audit.record("actor-" + tag, "LOGIN_FAILED", "User", "u-" + i, Map.of("reason", "BAD_PASSWORD"), "198.51.100." + i);
        }
    }

    /** What a sophisticated attacker does: change one row, then make every later hash consistent again. */
    @SuppressWarnings("unchecked")
    void rewriteHistory(long victimId, String newIp) throws Exception {
        jdbc.sql("ALTER TABLE identity.audit_log DISABLE TRIGGER trg_audit_immutable").update();
        try {
            jdbc.sql("UPDATE identity.audit_log SET ip = :ip WHERE id = :id").param("ip", newIp).param("id", victimId).update();
            var rows = jdbc.sql("""
                    SELECT id, actor_id, action, target_type, target_id, detail::text, ip, occurred_at, prev_hash
                    FROM identity.audit_log WHERE id >= :id ORDER BY id""").param("id", victimId)
                    .query((rs, i) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), rs.getObject(8, java.time.OffsetDateTime.class),
                            rs.getBytes(9)}).list();
            byte[] prev = (byte[]) rows.getFirst()[8];
            for (Object[] r : rows) {
                String detail = canonical.writeValueAsString(new TreeMap<>(canonical.readValue((String) r[5], Map.class)));
                Instant at = ((java.time.OffsetDateTime) r[7]).toInstant();
                byte[] h = AuditService.chainHash(prev, (String) r[1], (String) r[2], (String) r[3], (String) r[4], detail, (String) r[6], at);
                jdbc.sql("UPDATE identity.audit_log SET prev_hash = :p, hash = :h WHERE id = :id")
                        .param("p", prev).param("h", h).param("id", r[0]).update();
                prev = h;
            }
        } finally {
            jdbc.sql("ALTER TABLE identity.audit_log ENABLE TRIGGER trg_audit_immutable").update();
        }
    }

    @Test
    void a_full_chain_rewrite_fools_verify_but_not_the_anchors() throws Exception {
        record(5, "early");
        AuditService.Anchor anchor = audit.head().orElseThrow();   // published to WORM at this moment
        record(5, "late");

        long victim = anchor.id() - 2;                             // an entry BEFORE the anchor
        rewriteHistory(victim, "10.0.0.1");

        assertThat(audit.verify().intact()).as("the chain alone is consistent again: the attack works on it").isTrue();
        var r = audit.verifyAgainst(List.of(anchor));
        assertThat(r.intact()).isFalse();
        assertThat(r.mismatchedAnchorIds()).containsExactly(anchor.id());
    }

    @Test
    void untouched_history_matches_its_anchors() {
        record(3, "a");
        var a1 = audit.head().orElseThrow();
        record(3, "b");
        var a2 = audit.head().orElseThrow();
        var r = audit.verifyAgainst(List.of(a1, a2));
        assertThat(r.intact()).isTrue();
        assertThat(r.anchorsChecked()).isEqualTo(2);
    }

    @Test
    void deleting_an_anchored_row_is_detected() {
        record(3, "d");
        var anchor = audit.head().orElseThrow();
        jdbc.sql("ALTER TABLE identity.audit_log DISABLE TRIGGER trg_audit_immutable").update();
        try {
            jdbc.sql("DELETE FROM identity.audit_log WHERE id = :id").param("id", anchor.id()).update();
        } finally {
            jdbc.sql("ALTER TABLE identity.audit_log ENABLE TRIGGER trg_audit_immutable").update();
        }
        var r = audit.verifyAgainst(List.of(anchor));
        assertThat(r.intact()).isFalse();
        assertThat(r.missingAnchorIds()).containsExactly(anchor.id());
    }

    @Test
    void the_publisher_writes_each_new_head_once(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path f = dir.resolve("anchors.jsonl");
        AuditAnchorPublisher p = new AuditAnchorPublisher(audit, f.toString());
        record(1, "p");
        p.publish();
        p.publish();                              // no new entries: no duplicate anchor
        record(1, "q");
        p.publish();
        List<String> lines = java.nio.file.Files.readAllLines(f);
        assertThat(lines).hasSize(2);
        assertThat(lines.getLast()).contains("\"id\":" + audit.head().orElseThrow().id()).contains(audit.head().orElseThrow().hash());
    }
}
