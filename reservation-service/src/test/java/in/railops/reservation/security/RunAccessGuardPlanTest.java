package in.railops.reservation.security;

import in.railops.common.security.ScopeEvaluator;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Who may create runs from timetables: only admins with a GLOBAL grant. */
class RunAccessGuardPlanTest {

    final RunAccessGuard guard = new RunAccessGuard(null, new ScopeEvaluator());

    static JwtAuthenticationToken token(List<String> roles, List<String> grants) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("staff-1")
                .claim("roles", roles).claim("grants", grants)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(jwt, List.of());
    }

    @Test
    void a_global_admin_may_plan_runs() {
        assertThat(guard.canPlanRuns(token(List.of("ADMIN"), List.of("ADMIN:GLOBAL:*")))).isTrue();
    }

    @Test
    void scoped_admins_controllers_and_inconsistent_tokens_may_not() {
        assertThat(guard.canPlanRuns(token(List.of("ADMIN"), List.of("ADMIN:ZONE:WR")))).as("zonal admin").isFalse();
        assertThat(guard.canPlanRuns(token(List.of("CONTROLLER"), List.of("CONTROLLER:GLOBAL:*")))).as("controller").isFalse();
        assertThat(guard.canPlanRuns(token(List.of("CONTROLLER"), List.of("ADMIN:GLOBAL:*"))))
                .as("grant for a role the token doesn't hold").isFalse();
        assertThat(guard.canPlanRuns(null)).isFalse();
    }
}
