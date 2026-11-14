package in.railops.identity.it;
import tools.jackson.databind.json.JsonMapper;
import in.railops.identity.service.AuthService;
import in.railops.testsupport.ItPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 4D: change password and close account, on real PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AccountIT {

    static final ItPostgres.Db DB = ItPostgres.newDatabase();
    static final String PW = "central-railway-123";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::url);
        r.add("spring.datasource.username", DB::user);
        r.add("spring.datasource.password", DB::password);
        r.add("railops.audit.consume", () -> "false");
        r.add("spring.kafka.admin.auto-create", () -> "false");
        r.add("spring.kafka.bootstrap-servers", () -> "127.0.0.1:1");
    }

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JdbcClient jdbc;
    final ObjectMapper json = new JsonMapper();

    record Passenger(UUID id, String email, String access, String refresh) {
    }

    Passenger signedIn() throws Exception {
        String email = "acct." + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        UUID id = auth.registerPassenger(email, PW, "9" + (100_000_000 + ThreadLocalRandom.current().nextInt(800_000_000)), "127.0.0.1");
        var b = json.readTree(post("/api/v1/auth/app/login", null, Map.of("email", email, "password", PW)).getContentAsString());
        return new Passenger(id, email, b.get("accessToken").asString(), b.get("refreshToken").asString());
    }

    MockHttpServletResponse post(String path, String bearer, Map<String, String> body) throws Exception {
        var req = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (bearer != null) {
            req.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(req).andReturn().getResponse();
    }

    int login(String email, String password) throws Exception {
        return post("/api/v1/auth/app/login", null, Map.of("email", email, "password", password)).getStatus();
    }

    @Test
    void change_password_needs_the_current_one_and_ends_every_session() throws Exception {
        Passenger p = signedIn();
        var r = post("/api/v1/auth/password", p.access(), Map.of("currentPassword", PW, "newPassword", "western-railway-456"));
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(204);
        assertThat(login(p.email(), PW)).isEqualTo(401);
        assertThat(login(p.email(), "western-railway-456")).isEqualTo(200);
        assertThat(post("/api/v1/auth/app/refresh", null, Map.of("refreshToken", p.refresh())).getStatus()).isEqualTo(401);
    }

    @Test
    void wrong_current_password_is_refused_and_counts_toward_lockout() throws Exception {
        Passenger p = signedIn();
        for (int i = 0; i < 5; i++) {
            assertThat(post("/api/v1/auth/password", p.access(),
                    Map.of("currentPassword", "guess-number-" + i, "newPassword", "western-railway-456")).getStatus()).isEqualTo(401);
        }
        // Locked now: even the right password is refused, by both login and the account endpoints.
        assertThat(login(p.email(), PW)).isEqualTo(401);
        assertThat(post("/api/v1/auth/password", p.access(), Map.of("currentPassword", PW, "newPassword", "western-railway-456"))
                .getStatus()).isEqualTo(401);
    }

    @Test
    void weak_or_unchanged_new_password_is_refused() throws Exception {
        Passenger p = signedIn();
        assertThat(post("/api/v1/auth/password", p.access(), Map.of("currentPassword", PW, "newPassword", "short")).getStatus()).isEqualTo(422);
        var same = post("/api/v1/auth/password", p.access(), Map.of("currentPassword", PW, "newPassword", PW));
        assertThat(same.getStatus()).isEqualTo(422);
        assertThat(same.getContentAsString()).contains("PASSWORD_REUSED");
        assertThat(login(p.email(), PW)).isEqualTo(200);
    }

    @Test
    void no_token_or_a_forged_token_is_refused() throws Exception {
        assertThat(post("/api/v1/auth/password", null, Map.of("currentPassword", PW, "newPassword", "western-railway-456")).getStatus()).isEqualTo(401);
        assertThat(post("/api/v1/auth/erase", "eyJhbGciOiJub25lIn0.eyJzdWIiOiJ4In0.", Map.of("password", PW)).getStatus()).isEqualTo(401);
    }

    @Test
    void erase_removes_personal_data_roles_and_sessions_and_frees_the_email() throws Exception {
        Passenger p = signedIn();
        var r = post("/api/v1/auth/erase", p.access(), Map.of("password", PW));
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(204);

        var row = jdbc.sql("select email, phone_hmac, phone_enc, mfa_secret_enc, status from identity.app_user where id = :id")
                .param("id", p.id()).query().singleRow();
        assertThat(row.get("email")).isNull();
        assertThat(row.get("phone_hmac")).isNull();
        assertThat(row.get("phone_enc")).isNull();
        assertThat(row.get("mfa_secret_enc")).isNull();
        assertThat(row.get("status")).isEqualTo("ERASED");
        assertThat(jdbc.sql("select count(*) from identity.user_role where user_id = :id").param("id", p.id()).query(Long.class).single()).isZero();

        assertThat(post("/api/v1/auth/app/refresh", null, Map.of("refreshToken", p.refresh())).getStatus()).isEqualTo(401);
        assertThat(login(p.email(), PW)).isEqualTo(401);
        // A second erase with the still-unexpired access token finds nothing to verify against.
        assertThat(post("/api/v1/auth/erase", p.access(), Map.of("password", PW)).getStatus()).isEqualTo(401);
        // The email can be registered again as a brand-new account.
        auth.registerPassenger(p.email(), PW, "9" + (100_000_000 + ThreadLocalRandom.current().nextInt(800_000_000)), "127.0.0.1");
        assertThat(login(p.email(), PW)).isEqualTo(200);
    }

    @Test
    void erase_with_a_wrong_password_changes_nothing() throws Exception {
        Passenger p = signedIn();
        assertThat(post("/api/v1/auth/erase", p.access(), Map.of("password", "not-my-password-1")).getStatus()).isEqualTo(401);
        assertThat(login(p.email(), PW)).isEqualTo(200);
    }
}
