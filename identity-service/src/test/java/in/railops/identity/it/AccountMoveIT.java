package in.railops.identity.it;

import in.railops.identity.raillens.AccountMoveService;
import in.railops.identity.raillens.RailLensAccounts;
import in.railops.identity.raillens.RailLensAccounts.Link;
import in.railops.identity.raillens.RailLensAccounts.LinkResult;
import in.railops.identity.raillens.RailLensAccounts.Pending;
import in.railops.identity.service.AuthService;
import in.railops.testsupport.ItPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Phase 4C on real PostgreSQL: RailLens accounts moved in with their passwords, safely repeatable. */
@SpringBootTest
@ActiveProfiles("local")
class AccountMoveIT {

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

    @MockitoBean RailLensAccounts raillens;
    @Autowired AccountMoveService moves;
    @Autowired AuthService auth;
    @Autowired JdbcClient jdbc;

    static final String PASSWORD = "central-railway-123";
    final String bcrypt = new BCryptPasswordEncoder().encode(PASSWORD);
    final UUID admin = UUID.randomUUID();
    final List<Link> sentLinks = new ArrayList<>();
    final String tag = UUID.randomUUID().toString().substring(0, 8);
    final long base = ThreadLocalRandom.current().nextLong(1_000_000, 900_000_000);

    List<Pending> raillensAccounts;

    String email(String who) {
        return who + "." + tag + "@Example.com";   // mixed case on purpose: RailOps stores lower case
    }

    @BeforeEach
    void raillensHasThreeAccounts() {
        raillensAccounts = List.of(
                new Pending(base + 1, email("asha"), bcrypt, false),
                new Pending(base + 2, email("ravi"), bcrypt, true),
                new Pending(base + 3, email("broken"), "not-a-bcrypt-hash", false));
        when(raillens.pending(anyLong(), anyInt(), eq("admin-token"))).thenAnswer(inv -> {
            long after = inv.getArgument(0);
            return raillensAccounts.stream().filter(p -> p.raillensUserId() > after).toList();
        });
        when(raillens.link(anyList(), eq("admin-token"))).thenAnswer(inv -> {
            List<Link> links = inv.getArgument(0);
            sentLinks.addAll(links);
            return new LinkResult(links.size(), 0, List.of());
        });
    }

    @Test
    void moved_people_log_in_with_their_old_password_and_it_is_upgraded() {
        var s = moves.move(admin, "admin-token", "127.0.0.1");
        assertThat(s.created()).isEqualTo(2);
        assertThat(s.invalid()).containsExactly(base + 3);
        assertThat(s.raillensAdmins()).as("listed, not granted").containsExactly(base + 2);
        assertThat(sentLinks).extracting(Link::raillensUserId).containsExactly(base + 1, base + 2);

        String asha = email("asha").toLowerCase();
        var login = auth.login(asha, PASSWORD, null, "it", "127.0.0.1");
        assertThat(login.failed()).isFalse();
        assertThat(jdbc.sql("SELECT password_hash FROM identity.app_user WHERE email = :e").param("e", asha)
                .query(String.class).single()).as("bcrypt upgraded at first login").startsWith("{argon2}");
        assertThat(jdbc.sql("SELECT count(*) FROM identity.user_role r JOIN identity.app_user u ON u.id = r.user_id WHERE u.email = :e")
                .param("e", email("ravi").toLowerCase()).query(Integer.class).single()).as("RailLens admin: passenger role only").isEqualTo(1);
    }

    @Test
    void repeating_the_move_creates_nothing_new_and_resends_the_links() {
        moves.move(admin, "admin-token", "127.0.0.1");
        UUID first = sentLinks.getFirst().railopsUserId();
        sentLinks.clear();
        var again = moves.move(admin, "admin-token", "127.0.0.1");
        assertThat(again.created()).isZero();
        assertThat(again.alreadyMoved()).isEqualTo(2);
        assertThat(sentLinks.getFirst().railopsUserId()).isEqualTo(first);
    }

    @Test
    void an_email_already_registered_here_is_reported_not_merged() {
        String phone = "9" + (100_000_000 + ThreadLocalRandom.current().nextInt(800_000_000));
        UUID existing = auth.registerPassenger(email("asha"), "someone-elses-pass-1", phone, "127.0.0.1");
        var s = moves.move(admin, "admin-token", "127.0.0.1");
        assertThat(s.emailTaken()).isEqualTo(1);
        assertThat(s.emailTakenSample().getFirst()).startsWith((base + 1) + " ").doesNotContain(email("asha").toLowerCase());
        assertThat(sentLinks).extracting(Link::raillensUserId).doesNotContain(base + 1);
        assertThat(auth.login(email("asha"), PASSWORD, null, "it", "127.0.0.1").failed())
                .as("the existing account keeps its own password").isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM identity.raillens_link WHERE user_id = :u").param("u", existing)
                .query(Integer.class).single()).isZero();
    }
}
