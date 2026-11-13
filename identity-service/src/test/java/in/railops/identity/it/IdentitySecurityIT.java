package in.railops.identity.it;

import in.railops.identity.service.AuditService;
import in.railops.identity.service.AuthService;
import in.railops.identity.service.TokenService;
import in.railops.testsupport.ItPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AUTH: session theft containment, brute-force lockout under concurrency, tamper-evident audit. Real PostgreSQL. */
@SpringBootTest
@ActiveProfiles("local")
class IdentitySecurityIT {

    static final ItPostgres.Db DB = ItPostgres.newDatabase();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::url);
        r.add("spring.datasource.username", DB::user);
        r.add("spring.datasource.password", DB::password);
        r.add("railops.audit.consume", () -> "false");
        r.add("spring.kafka.admin.auto-create", () -> "false");
        r.add("spring.kafka.bootstrap-servers", () -> "127.0.0.1:1");
    }

    @Autowired AuthService auth;
    @Autowired AuditService audit;
    @Autowired JdbcClient jdbc;

    String newPassenger() {
        String email = "it." + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        auth.registerPassenger(email, "central-railway-123", "9" + (100_000_000 + ThreadLocalRandom.current().nextInt(800_000_000)), "127.0.0.1");
        return email;
    }

    TokenService.IssuedTokens login(String email) {
        var out = auth.login(email, "central-railway-123", null, "it", "127.0.0.1");
        assertThat(out.failed()).isFalse();
        return out.result().session();
    }

    @Test
    void rotation_issues_a_new_refresh_token_and_old_one_reused_later_revokes_the_whole_family() {
        var s1 = login(newPassenger());
        var s2 = auth.refresh(s1.refreshToken(), "it", "127.0.0.1");
        var s3 = auth.refresh(s2.refreshToken(), "it", "127.0.0.1");
        assertThat(s3.refreshToken()).isNotEqualTo(s2.refreshToken());

        // An attacker replays the stolen s1 after the grace window: treat as theft.
        jdbc.sql("UPDATE identity.refresh_token SET replaced_at = replaced_at - interval '1 minute' WHERE replaced_at IS NOT NULL").update();
        assertThatThrownBy(() -> auth.refresh(s1.refreshToken(), "it", "127.0.0.1"));
        // ... and the legitimate holder's latest token dies with it: both parties must log in again.
        assertThatThrownBy(() -> auth.refresh(s3.refreshToken(), "it", "127.0.0.1"));
        assertThat(jdbc.sql("SELECT count(*) FROM identity.audit_log WHERE action = 'REFRESH_TOKEN_REUSE'").query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    void two_tabs_refreshing_at_once_are_not_mistaken_for_theft() {
        var s1 = login(newPassenger());
        var a = auth.refresh(s1.refreshToken(), "tab-a", "127.0.0.1");
        var b = auth.refresh(s1.refreshToken(), "tab-b", "127.0.0.1");   // within the 5 s grace window
        assertThat(auth.refresh(a.refreshToken(), "tab-a", "127.0.0.1")).isNotNull();
        assertThat(auth.refresh(b.refreshToken(), "tab-b", "127.0.0.1")).isNotNull();
    }

    @Test
    void concurrent_wrong_passwords_lock_the_account_and_the_right_password_then_fails_too() throws Exception {
        String email = newPassenger();
        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CyclicBarrier go = new CyclicBarrier(n);
        List<Future<Boolean>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                return auth.login(email, "wrong-password-" + UUID.randomUUID(), null, "it", "10.0.0.9").failed();
            }));
        }
        for (Future<Boolean> f : fs) assertThat(f.get(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(auth.login(email, "central-railway-123", null, "it", "127.0.0.1").failed())
                .as("locked: the correct password must not work during lockout").isTrue();
        // Row lock (SELECT ... FOR UPDATE) serialises the 20 attempts: exactly MAX_FAILED_LOGINS are judged on the
        // password, the rest bounce off the lock. Lost updates would let more than 5 guesses through.
        Integer judged = jdbc.sql("""
                SELECT count(*) FROM identity.audit_log a JOIN identity.app_user u ON u.id::text = a.actor_id
                WHERE u.email = :e AND a.action = 'LOGIN_FAILED' AND a.detail->>'reason' = 'BAD_PASSWORD'""")
                .param("e", email).query(Integer.class).single();
        assertThat(judged).as("password guesses actually evaluated").isEqualTo(5);
        assertThat(jdbc.sql("SELECT locked_until > now() FROM identity.app_user WHERE email = :e").param("e", email)
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void the_audit_chain_detects_an_edited_row() {
        String email = newPassenger();
        login(email);
        assertThat(audit.verify().intact()).isTrue();

        // A DBA (table owner) bypasses the immutability trigger and rewrites history.
        Long victim = jdbc.sql("SELECT min(id) FROM identity.audit_log WHERE action = 'LOGIN'").query(Long.class).single();
        jdbc.sql("ALTER TABLE identity.audit_log DISABLE TRIGGER trg_audit_immutable").update();
        try {
            jdbc.sql("UPDATE identity.audit_log SET ip = '203.0.113.7' WHERE id = :id").param("id", victim).update();
        } finally {
            jdbc.sql("ALTER TABLE identity.audit_log ENABLE TRIGGER trg_audit_immutable").update();
        }
        AuditService.VerifyResult r = audit.verify();
        assertThat(r.intact()).isFalse();
        assertThat(r.firstBrokenId()).isEqualTo(victim);
        // Detects, doesn't prevent: an attacker who can rewrite every later hash too is caught only by the
        // externally anchored head (WORM export, Phase 12).
    }

    @Test
    void audit_rows_cannot_be_changed_through_normal_sql() {
        newPassenger();
        assertThatThrownBy(() -> jdbc.sql("UPDATE identity.audit_log SET action = 'X'").update());
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM identity.audit_log").update());
    }
}
