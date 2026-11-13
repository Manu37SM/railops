package in.railops.identity.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import in.railops.common.crypto.FieldCipher;
import in.railops.common.security.RailopsClaims;
import in.railops.common.security.RailopsJwtAuthenticationConverter;
import in.railops.common.security.RailopsJwtValidators;
import in.railops.identity.crypto.JwtKeys;
import in.railops.identity.service.PhoneTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Configuration
public class IdentitySecurityConfig {

    @Bean
    SecurityFilterChain identityChain(HttpSecurity http) throws Exception {
        org.springframework.security.authorization.AuthorizationManager<
                org.springframework.security.web.access.intercept.RequestAuthorizationContext> adminWithMfa =
                AuthorizationManagers.allOf(AuthorityAuthorizationManager.hasRole("ADMIN"),
                        AuthorityAuthorizationManager.hasAuthority("AMR_otp"));
        org.springframework.security.authorization.AuthorizationManager<
                org.springframework.security.web.access.intercept.RequestAuthorizationContext> auditorWithMfa =
                AuthorizationManagers.allOf(AuthorityAuthorizationManager.hasAnyRole("ADMIN", "AUDITOR"),
                        AuthorityAuthorizationManager.hasAuthority("AMR_otp"));
        http
                .csrf(c -> c.disable()) // see AuthController: SameSite=Strict + custom-header check on cookie endpoints
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, "/oauth2/token").permitAll() // client auth inside
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
                                "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/app/login", "/api/v1/auth/app/refresh",
                                "/api/v1/auth/app/logout").permitAll()
                        // Phase 4D: user access tokens only (enrolment and service tokens carry no AMR_pwd)
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/password", "/api/v1/auth/erase").hasAuthority("AMR_pwd")
                        .requestMatchers("/api/v1/auth/mfa/**").hasAuthority("SCOPE_mfa_enroll")
                        .requestMatchers("/api/v1/admin/audit/**").access(auditorWithMfa)
                        .requestMatchers("/api/v1/admin/**").access(adminWithMfa)
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(new RailopsJwtAuthenticationConverter())))
                .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'")));
        return http.build();
    }

    @Bean
    JwtKeys jwtKeys(Environment env,
                    @Value("${railops.jwt.private-key-pem:}") String privatePem,
                    @Value("${railops.jwt.previous-public-keys-pem:}") String previousPems) {
        if (privatePem.isBlank()) {
            if (env.acceptsProfiles(Profiles.of("local", "test"))) {
                return JwtKeys.generateEphemeral();
            }
            throw new IllegalStateException("railops.jwt.private-key-pem must be configured outside local/test");
        }
        List<String> previous = previousPems.isBlank() ? List.of()
                : Arrays.asList(previousPems.split("(?=-----BEGIN PUBLIC KEY-----)"));
        return JwtKeys.fromPem(privatePem, previous);
    }

    @Bean
    JwtEncoder jwtEncoder(JwtKeys keys) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.signingKey())));
    }

    /** Verifies tokens with our own public keys, no network call to ourselves. Accepts API and enrolment audiences. */
    @Bean
    JwtDecoder jwtDecoder(JwtKeys keys, @Value("${railops.security.issuer}") String issuer) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256,
                new ImmutableJWKSet<>(keys.publicJwkSet())));
        processor.setJWTClaimsSetVerifier((claims, ctx) -> { }); // claims are validated by Spring validators below
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(RailopsJwtValidators.forAudiences(issuer,
                Set.of(RailopsClaims.AUDIENCE_API, RailopsClaims.AUDIENCE_MFA)));
        return decoder;
    }

    /** Argon2id by default; bcrypt kept only to verify legacy hashes, which are upgraded on next login. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("argon2", Map.of(
                "argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(),
                "bcrypt", new BCryptPasswordEncoder()));
    }

    @Bean
    FieldCipher fieldCipher(@Value("${railops.crypto.key-id:1}") int keyId, @Value("${railops.crypto.enc-key}") String key) {
        return new FieldCipher(keyId, key);
    }

    @Bean
    PhoneTokens phoneTokens(@Value("${railops.crypto.hmac-key}") String key) {
        return new PhoneTokens(key);
    }

    @Bean
    org.apache.kafka.clients.admin.NewTopic auditDlt(@Value("${railops.kafka.replicas:3}") int r) {
        return in.railops.common.messaging.Topics.dlt(in.railops.common.messaging.Topics.AUDIT + ".identity", 6, r);
    }

    @Bean
    org.springframework.kafka.listener.DefaultErrorHandler kafkaErrorHandler(
            org.springframework.kafka.core.KafkaTemplate<String, String> template,
            io.micrometer.core.instrument.MeterRegistry meters) {
        return in.railops.common.messaging.DeadLetters.errorHandler(template, meters, "identity");
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${railops.web.allowed-origins:}") List<String> origins) {
        CorsConfiguration auth = new CorsConfiguration();
        auth.setAllowedOrigins(origins);          // exact origins only; never "*" with credentials
        auth.setAllowedMethods(List.of("POST"));
        auth.setAllowedHeaders(List.of("Content-Type", "Authorization", "X-Requested-With"));
        auth.setAllowCredentials(true);
        auth.setMaxAge(600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/api/v1/auth/**", auth);
        return src;
    }
}
