package in.railops.identity.it;
import tools.jackson.databind.json.JsonMapper;
import in.railops.identity.service.AuthService;
import in.railops.testsupport.ItPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Phase 4A: app sessions (refresh token in the body) on real PostgreSQL, and the email claim. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AppSessionIT {

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

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    final ObjectMapper json = new JsonMapper();

    String newPassenger() {
        String email = "app." + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        auth.registerPassenger(email, "central-railway-123", "9" + (100_000_000 + ThreadLocalRandom.current().nextInt(800_000_000)), "127.0.0.1");
        return email;
    }

    MockHttpServletResponse postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
    }

    JsonNode body(MockHttpServletResponse r) throws Exception {
        return json.readTree(r.getContentAsString());
    }

    static JsonNode claims(String jwt) throws Exception {
        return new JsonMapper().readTree(new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8));
    }

    @Test
    void login_returns_both_tokens_in_the_body_and_sets_no_cookie() throws Exception {
        String email = newPassenger();
        var r = postJson("/api/v1/auth/app/login", "{\"email\":\"%s\",\"password\":\"central-railway-123\"}".formatted(email));
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        JsonNode b = body(r);
        assertThat(b.get("accessToken").asString()).isNotBlank();
        assertThat(b.get("refreshToken").asString()).isNotBlank();
        assertThat(b.get("refreshExpiresIn").asLong()).isEqualTo(14L * 24 * 3600);
        assertThat(r.getHeader("Set-Cookie")).isNull();
        assertThat(r.getHeader("Cache-Control")).contains("no-store");
        assertThat(claims(b.get("accessToken").asString()).get("email").asString()).isEqualTo(email);
    }

    @Test
    void refresh_rotates_and_the_old_token_stops_working() throws Exception {
        String email = newPassenger();
        String rt1 = body(postJson("/api/v1/auth/app/login", "{\"email\":\"%s\",\"password\":\"central-railway-123\"}".formatted(email)))
                .get("refreshToken").asString();
        var r2 = postJson("/api/v1/auth/app/refresh", "{\"refreshToken\":\"%s\"}".formatted(rt1));
        assertThat(r2.getStatus()).isEqualTo(200);
        String rt2 = body(r2).get("refreshToken").asString();
        assertThat(rt2).isNotEqualTo(rt1);
        assertThat(postJson("/api/v1/auth/app/refresh", "{\"refreshToken\":\"%s\"}".formatted(rt2)).getStatus()).isEqualTo(200);
    }

    @Test
    void logout_ends_the_session() throws Exception {
        String email = newPassenger();
        String rt = body(postJson("/api/v1/auth/app/login", "{\"email\":\"%s\",\"password\":\"central-railway-123\"}".formatted(email)))
                .get("refreshToken").asString();
        assertThat(postJson("/api/v1/auth/app/logout", "{\"refreshToken\":\"%s\"}".formatted(rt)).getStatus()).isEqualTo(204);
        assertThat(postJson("/api/v1/auth/app/refresh", "{\"refreshToken\":\"%s\"}".formatted(rt)).getStatus()).isEqualTo(401);
    }

    @Test
    void wrong_password_and_garbage_tokens_are_refused() throws Exception {
        String email = newPassenger();
        assertThat(postJson("/api/v1/auth/app/login", "{\"email\":\"%s\",\"password\":\"wrong-password-123\"}".formatted(email))
                .getStatus()).isEqualTo(401);
        assertThat(postJson("/api/v1/auth/app/refresh", "{\"refreshToken\":\"not-a-real-token\"}").getStatus()).isEqualTo(401);
        assertThat(postJson("/api/v1/auth/app/refresh", "{}").getStatus()).isEqualTo(400);
    }

    @Test
    void the_browser_endpoints_are_unchanged_cookie_and_no_refresh_token_in_the_body() throws Exception {
        String email = newPassenger();
        var r = postJson("/api/v1/auth/login", "{\"email\":\"%s\",\"password\":\"central-railway-123\"}".formatted(email));
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getHeader("Set-Cookie")).contains("HttpOnly");
        assertThat(body(r).has("refreshToken")).isFalse();
    }
}
