package in.railops.reservation.config;

import in.railops.common.security.RailopsClaims;
import in.railops.common.security.RailopsJwtAuthenticationConverter;
import in.railops.common.security.RailopsJwtValidators;
import in.railops.common.security.ScopeEvaluator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import java.util.Set;

/**
 * Stateless resource server. CSRF is off because nothing here authenticates by cookie: every call
 * carries a bearer token the browser won't attach on its own. (The refresh cookie lives only on
 * identity-service's /auth path.)
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // Scraped from inside the cluster; the network policy (Phase 12) blocks external access.
                        .requestMatchers("/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/runs/*/availability").permitAll()
                        // Phase 5: run lookup (stops, classes) for passenger apps. Public like availability.
                        .requestMatchers(HttpMethod.GET, "/api/v1/runs", "/api/v1/runs/*", "/api/v1/runs/*/seat-map").permitAll()
                        // Advisory WL estimate for the next WL number: public like availability. 404 when disabled.
                        .requestMatchers(HttpMethod.GET, "/api/v1/runs/*/wl-prediction").permitAll()
                        // API docs: served only where springdoc is enabled (off in prod via SPRINGDOC_ENABLED=false).
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/bookings").hasAnyRole("PASSENGER", "AGENT")
                        .requestMatchers("/api/v1/bookings/**").hasAnyRole("PASSENGER", "AGENT")
                        .requestMatchers("/api/v1/runs/*/waiting-room").hasAnyRole("PASSENGER", "AGENT")
                        // Staff endpoints: MFA is mandatory, then fine-grained scope checks in @PreAuthorize.
                        .requestMatchers("/api/v1/staff/**").hasAuthority("AMR_otp")
                        // Service tokens only (passenger tokens never carry scopes); see InternalContactController.
                        .requestMatchers(HttpMethod.GET, "/internal/v1/bookings/*/contact").hasAuthority("SCOPE_contacts:read")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(new RailopsJwtAuthenticationConverter())))
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
        return http.build();
    }

    /**
     * Keys come from identity-service's JWKS and are cached by Nimbus; an unknown {@code kid} (after key
     * rotation) triggers a refetch. Only RS256 is accepted: never let the token choose its own algorithm.
     */
    @Bean
    JwtDecoder jwtDecoder(@Value("${railops.security.jwk-set-uri}") String jwkSetUri,
                          @Value("${railops.security.issuer}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        // Both audiences: user tokens for /api/**, service tokens for /internal/**. Path rules keep them apart:
        // service tokens have no ROLE_*, user tokens have no SCOPE_*.
        decoder.setJwtValidator(RailopsJwtValidators.forAudiences(issuer,
                Set.of(RailopsClaims.AUDIENCE_API, RailopsClaims.AUDIENCE_INTERNAL)));
        return decoder;
    }

    @Bean("scopes")
    ScopeEvaluator scopeEvaluator() {
        return new ScopeEvaluator();
    }
}
