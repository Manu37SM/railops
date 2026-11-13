package in.railops.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Token claims -> authorities. The converter is the last line before @PreAuthorize: injection here is privilege escalation. */
class RailopsJwtAuthenticationConverterTest {

    final RailopsJwtAuthenticationConverter converter = new RailopsJwtAuthenticationConverter();

    Jwt jwt(Map<String, Object> claims, List<String> aud) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").subject("7c9e6679-7425-40de-944b-e07fc1f90ae7")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).audience(aud);
        claims.forEach(b::claim);
        return b.build();
    }

    List<String> authorities(Jwt jwt) {
        return converter.convert(jwt).getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList();
    }

    @Test
    void maps_roles_amr_and_service_scopes() {
        var a = authorities(jwt(Map.of(RailopsClaims.ROLES, List.of("PASSENGER", "AGENT"), RailopsClaims.AMR, List.of("pwd", "otp"),
                "scope", "payments:create bookings:read"), List.of(RailopsClaims.AUDIENCE_API)));
        assertThat(a).containsExactly("AMR_otp", "AMR_pwd", "ROLE_AGENT", "ROLE_PASSENGER", "SCOPE_bookings:read", "SCOPE_payments:create");
    }

    @Test
    void hostile_claim_values_are_ignored_not_mapped() {
        var a = authorities(jwt(Map.of(
                RailopsClaims.ROLES, List.of("ADMIN,PASSENGER", "ROLE_ADMIN ", "admin", "A", "PASSENGER"),
                RailopsClaims.AMR, List.of("OTP", "pwd otp", "x"),
                "scope", "payments:create SCOPE_x contacts:read:all ../../"), List.of(RailopsClaims.AUDIENCE_API)));
        // "ROLE_ADMIN " has a trailing space; "admin" is lower case; "A" too short: none become authorities.
        assertThat(a).containsExactly("ROLE_PASSENGER", "SCOPE_payments:create");
    }

    @Test
    void mfa_enrolment_audience_grants_only_the_enrolment_scope() {
        var a = authorities(jwt(Map.of(), List.of(RailopsClaims.AUDIENCE_MFA)));
        assertThat(a).containsExactly("SCOPE_mfa_enroll");
    }

    @Test
    void missing_claims_mean_no_authorities() {
        assertThat(authorities(jwt(Map.of(), List.of(RailopsClaims.AUDIENCE_API)))).isEmpty();
    }
}
