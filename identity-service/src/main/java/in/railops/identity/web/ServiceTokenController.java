package in.railops.identity.web;

import in.railops.common.security.RailopsClaims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * OAuth2 client_credentials (RFC 6749 §4.4) for service identities.
 *
 * <p>Service tokens use their own audience ({@code railops-internal}) so a leaked service token is
 * useless on public APIs and a user token is useless on internal ones. Requested scopes must be a
 * subset of the client's allowed scopes. Errors follow RFC 6749 §5.2 ({@code invalid_client} etc.).
 */
@RestController
public class ServiceTokenController {

    public static final String AUDIENCE_INTERNAL = "railops-internal";
    private static final Duration TTL = Duration.ofMinutes(5);

    private record Client(String id, String secretHash, Set<String> scopes, boolean enabled) {
    }

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final String issuer;
    private final String dummyHash;

    public ServiceTokenController(JdbcClient jdbc, PasswordEncoder passwords, JwtEncoder encoder,
                                  @Value("${railops.security.issuer}") String issuer) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.encoder = encoder;
        this.issuer = issuer;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "OAuth2 client_credentials token for services", description = "HTTP Basic client authentication. Tokens have audience railops-internal and live 5 minutes.")
    @PostMapping(path = "/oauth2/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> token(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String basic,
                                                     @RequestParam("grant_type") String grantType,
                                                     @RequestParam(name = "scope", required = false) String scope) {
        if (!"client_credentials".equals(grantType)) {
            return error(HttpStatus.BAD_REQUEST, "unsupported_grant_type");
        }
        String[] creds = decodeBasic(basic);
        if (creds == null) {
            return error(HttpStatus.UNAUTHORIZED, "invalid_client");
        }
        Optional<Client> client = jdbc.sql("""
                        SELECT client_id, secret_hash, allowed_scopes, enabled FROM identity.service_client WHERE client_id = :id
                        """)
                .param("id", creds[0])
                .query((rs, n) -> new Client(rs.getString(1), rs.getString(2),
                        Set.copyOf(Arrays.asList((String[]) rs.getArray(3).getArray())), rs.getBoolean(4)))
                .optional();
        boolean secretOk = passwords.matches(creds[1], client.map(Client::secretHash).orElse(dummyHash));
        if (client.isEmpty() || !secretOk || !client.get().enabled()) {
            return error(HttpStatus.UNAUTHORIZED, "invalid_client");
        }
        Set<String> requested = scope == null || scope.isBlank()
                ? client.get().scopes() : Set.of(scope.trim().split("\\s+"));
        if (!client.get().scopes().containsAll(requested)) {
            return error(HttpStatus.BAD_REQUEST, "invalid_scope");
        }
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer).subject("svc:" + client.get().id())
                .audience(List.of(AUDIENCE_INTERNAL))
                .issuedAt(now).expiresAt(now.plus(TTL)).id(UUID.randomUUID().toString())
                .claim("scope", String.join(" ", requested.stream().sorted().toList()))
                .claim(RailopsClaims.USER_TYPE, "SERVICE")
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("access_token", token, "token_type", "Bearer", "expires_in", TTL.toSeconds(),
                        "scope", String.join(" ", requested)));
    }

    private static String[] decodeBasic(String header) {
        if (header == null || !header.startsWith("Basic ")) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
            int i = decoded.indexOf(':');
            return i <= 0 ? null : new String[]{decoded.substring(0, i), decoded.substring(i + 1)};
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code) {
        var b = ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (status == HttpStatus.UNAUTHORIZED) {
            b = b.header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"railops\"");
        }
        return b.body(Map.of("error", code));
    }
}
