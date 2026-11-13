package in.railops.identity.service;

import in.railops.common.security.RailopsClaims;
import in.railops.common.security.ScopeGrant;
import in.railops.identity.domain.AppUser;
import in.railops.identity.domain.RefreshToken;
import in.railops.identity.domain.Repositories.RefreshTokenRepository;
import in.railops.identity.domain.Repositories.UserRoleRepository;
import in.railops.identity.domain.UserRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class TokenService {

    public record IssuedTokens(String accessToken, long expiresInSeconds, String refreshToken, Duration refreshTtl,
                               UUID refreshTokenId) {
    }

    static final Duration ACCESS_TTL = Duration.ofMinutes(10);
    static final Duration ENROLMENT_TTL = Duration.ofMinutes(5);
    static final Duration PASSENGER_REFRESH_TTL = Duration.ofDays(14);
    static final Duration STAFF_REFRESH_TTL = Duration.ofHours(12); // a shift, then full re-authentication

    private final JwtEncoder encoder;
    private final RefreshTokenRepository refreshTokens;
    private final UserRoleRepository roles;
    private final String issuer;
    private final SecureRandom random = new SecureRandom();

    public TokenService(JwtEncoder encoder, RefreshTokenRepository refreshTokens, UserRoleRepository roles,
                        @Value("${railops.security.issuer}") String issuer) {
        this.encoder = encoder;
        this.refreshTokens = refreshTokens;
        this.roles = roles;
        this.issuer = issuer;
    }

    /** Access + refresh for a new session (new rotation family). */
    public IssuedTokens issueSession(AppUser user, List<String> amr, String userAgent, String ip) {
        return issue(user, amr, UUID.randomUUID(), userAgent, ip);
    }

    /** Access + refresh continuing an existing family (rotation). */
    public IssuedTokens issueRotation(AppUser user, List<String> amr, UUID familyId, String userAgent, String ip) {
        return issue(user, amr, familyId, userAgent, ip);
    }

    /** Short-lived token usable only on MFA enrolment endpoints (audience railops-mfa). */
    public String issueEnrolmentToken(AppUser user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer).subject(user.getId().toString())
                .audience(List.of(RailopsClaims.AUDIENCE_MFA))
                .issuedAt(now).expiresAt(now.plus(ENROLMENT_TTL)).id(UUID.randomUUID().toString())
                .claim(RailopsClaims.USER_TYPE, user.getUserType().name())
                .claim(RailopsClaims.AMR, List.of("pwd"))
                .build();
        return encode(claims);
    }

    public static byte[] hash(String rawToken) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private IssuedTokens issue(AppUser user, List<String> amr, UUID familyId, String userAgent, String ip) {
        Instant now = Instant.now();
        List<UserRole> held = roles.findByUserId(user.getId());
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(issuer).subject(user.getId().toString())
                .audience(List.of(RailopsClaims.AUDIENCE_API))
                .issuedAt(now).notBefore(now).expiresAt(now.plus(ACCESS_TTL)).id(UUID.randomUUID().toString())
                .claim(RailopsClaims.USER_TYPE, user.getUserType().name())
                .claim(RailopsClaims.ROLES, held.stream().map(UserRole::getRoleCode).distinct().sorted().toList())
                .claim(RailopsClaims.GRANTS, held.stream().map(UserRole::toGrant).map(ScopeGrant::encode).sorted().toList())
                .claim(RailopsClaims.AMR, amr);
        if (user.getEmail() != null) {
            // Phase 4A: lets RailLens match its accounts. Access tokens live 10 minutes and are never logged.
            builder.claim(RailopsClaims.EMAIL, user.getEmail());
        }
        JwtClaimsSet claims = builder.build();

        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Duration refreshTtl = user.getUserType() == AppUser.Type.PASSENGER ? PASSENGER_REFRESH_TTL : STAFF_REFRESH_TTL;
        UUID refreshId = UUID.randomUUID();
        refreshTokens.save(new RefreshToken(refreshId, user.getId(), familyId, hash(refresh),
                now.plus(refreshTtl), userAgent, ip));
        return new IssuedTokens(encode(claims), ACCESS_TTL.toSeconds(), refresh, refreshTtl, refreshId);
    }

    private String encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
