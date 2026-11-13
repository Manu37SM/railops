package in.railops.identity.service;

import in.railops.common.security.ScopeGrant;
import in.railops.identity.domain.AppUser;
import in.railops.identity.domain.Repositories.AppUserRepository;
import in.railops.identity.domain.Repositories.RefreshTokenRepository;
import in.railops.identity.domain.Repositories.UserRoleRepository;
import in.railops.identity.domain.UserRole;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class UserAdminService {

    public record CreatedStaff(UUID userId, String oneTimePassword) {
    }

    private static final Set<String> STAFF_ROLES = Set.of(
            "STATION_MASTER", "CONTROLLER", "CREW_CONTROLLER", "REVENUE_ANALYST", "ADMIN", "AUDITOR", "AGENT");
    /** Roles that only make sense system-wide. */
    private static final Set<String> GLOBAL_ONLY = Set.of("ADMIN", "AUDITOR", "REVENUE_ANALYST");

    private final AppUserRepository users;
    private final UserRoleRepository roles;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();

    public UserAdminService(AppUserRepository users, UserRoleRepository roles, RefreshTokenRepository refreshTokens,
                            PasswordEncoder passwords, AuditService audit) {
        this.users = users;
        this.roles = roles;
        this.refreshTokens = refreshTokens;
        this.passwords = passwords;
        this.audit = audit;
    }

    /**
     * Creates a staff account in PENDING_MFA with a one-time password, returned once to the admin for
     * out-of-band hand-over. First login forces MFA enrolment before any API access is possible.
     */
    @Transactional
    public CreatedStaff createStaff(UUID adminId, String email, String ip) {
        String otp = randomPassword();
        UUID id = UUID.randomUUID();
        try {
            users.saveAndFlush(new AppUser(id, AuthService.normalizeEmail(email), null, null, passwords.encode(otp),
                    AppUser.Type.STAFF, AppUser.Status.PENDING_MFA));
        } catch (DataIntegrityViolationException e) {
            throw new IdentityException("ACCOUNT_EXISTS", HttpStatus.CONFLICT, "an account with this email exists");
        }
        audit.record(adminId.toString(), "STAFF_CREATED", "User", id.toString(), Map.of("email", email), ip);
        return new CreatedStaff(id, otp);
    }

    @Transactional
    public void grant(UUID adminId, UUID userId, String role, ScopeGrant.ScopeType scopeType, String scopeValue, String ip) {
        if (adminId.equals(userId)) {
            // Separation of duties: nobody escalates their own privileges.
            throw new IdentityException("SELF_GRANT", HttpStatus.FORBIDDEN, "cannot change your own roles");
        }
        if (!STAFF_ROLES.contains(role)) {
            throw new IdentityException("ROLE_INVALID", HttpStatus.UNPROCESSABLE_ENTITY, "unknown or non-grantable role");
        }
        if (GLOBAL_ONLY.contains(role) != (scopeType == ScopeGrant.ScopeType.GLOBAL)) {
            throw new IdentityException("SCOPE_INVALID", HttpStatus.UNPROCESSABLE_ENTITY,
                    role + (GLOBAL_ONLY.contains(role) ? " must be GLOBAL" : " must be scoped to a zone, division or station"));
        }
        String value = scopeType == ScopeGrant.ScopeType.GLOBAL ? "*" : scopeValue;
        if (value == null || !value.matches("\\*|[A-Z]{2,6}")) {
            throw new IdentityException("SCOPE_INVALID", HttpStatus.UNPROCESSABLE_ENTITY, "invalid scope value");
        }
        AppUser user = users.findById(userId)
                .orElseThrow(() -> new IdentityException("USER_NOT_FOUND", HttpStatus.NOT_FOUND, "user not found"));
        if (user.getUserType() == AppUser.Type.PASSENGER) {
            throw new IdentityException("ROLE_INVALID", HttpStatus.UNPROCESSABLE_ENTITY, "passenger accounts cannot hold staff roles");
        }
        try {
            roles.saveAndFlush(new UserRole(userId, role, scopeType, value, adminId));
        } catch (DataIntegrityViolationException e) {
            throw new IdentityException("ROLE_EXISTS", HttpStatus.CONFLICT, "grant already exists");
        }
        audit.record(adminId.toString(), "ROLE_GRANTED", "User", userId.toString(),
                Map.of("role", role, "scope", scopeType + ":" + value), ip);
    }

    /**
     * Revocation also kills refresh tokens, so the next token can't carry the role. Already-issued access
     * tokens stay valid until expiry (≤ 10 min): the accepted cost of stateless JWTs.
     */
    @Transactional
    public void revoke(UUID adminId, long grantId, String ip) {
        UserRole r = roles.findById(grantId)
                .orElseThrow(() -> new IdentityException("GRANT_NOT_FOUND", HttpStatus.NOT_FOUND, "grant not found"));
        if (adminId.equals(r.getUserId())) {
            throw new IdentityException("SELF_GRANT", HttpStatus.FORBIDDEN, "cannot change your own roles");
        }
        roles.delete(r);
        refreshTokens.revokeAllForUser(r.getUserId(), Instant.now());
        audit.record(adminId.toString(), "ROLE_REVOKED", "User", r.getUserId().toString(),
                Map.of("role", r.getRoleCode(), "grantId", grantId), ip);
    }

    private String randomPassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(20);
        for (int i = 0; i < 20; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
