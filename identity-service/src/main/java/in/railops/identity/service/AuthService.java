package in.railops.identity.service;

import in.railops.common.crypto.FieldCipher;
import in.railops.common.security.ScopeGrant;
import in.railops.identity.crypto.Totp;
import in.railops.identity.domain.AppUser;
import in.railops.identity.domain.RefreshToken;
import in.railops.identity.domain.Repositories.AppUserRepository;
import in.railops.identity.domain.Repositories.RefreshTokenRepository;
import in.railops.identity.domain.Repositories.UserRoleRepository;
import in.railops.identity.domain.UserRole;
import in.railops.identity.service.TokenService.IssuedTokens;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    static final String AAD_MFA = "identity.app_user.mfa_secret";
    static final String AAD_PHONE = "identity.app_user.phone";
    /** Two tabs refreshing at once present the same token twice; within this window that's a race, not theft. */
    static final Duration ROTATION_GRACE = Duration.ofSeconds(5);

    /** Login outcome: either a full session, or an enrolment token for staff who must set up MFA first. */
    public record LoginResult(IssuedTokens session, String mfaEnrolmentToken) {
    }

    private final AppUserRepository users;
    private final UserRoleRepository roles;
    private final RefreshTokenRepository refreshTokens;
    private final TokenService tokens;
    private final AuditService audit;
    private final PasswordEncoder passwords;
    private final PasswordPolicy policy;
    private final FieldCipher cipher;
    private final PhoneTokens phoneTokens;
    private final MeterRegistry meters;
    /** Verified against when the user doesn't exist, so "no such user" costs the same time as "wrong password". */
    private final String dummyHash;

    public AuthService(AppUserRepository users, UserRoleRepository roles, RefreshTokenRepository refreshTokens,
                       TokenService tokens, AuditService audit, PasswordEncoder passwords, PasswordPolicy policy,
                       FieldCipher cipher, PhoneTokens phoneTokens, MeterRegistry meters) {
        this.users = users;
        this.roles = roles;
        this.refreshTokens = refreshTokens;
        this.tokens = tokens;
        this.audit = audit;
        this.passwords = passwords;
        this.policy = policy;
        this.cipher = cipher;
        this.phoneTokens = phoneTokens;
        this.meters = meters;
        this.dummyHash = passwords.encode("timing-equaliser-" + UUID.randomUUID());
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public UUID registerPassenger(String email, String password, String phone, String ip) {
        String normalized = normalizeEmail(email);
        policy.check(password, normalized);
        UUID id = UUID.randomUUID();
        AppUser user = new AppUser(id, normalized, phoneTokens.token(phone), cipher.encrypt(phone, AAD_PHONE),
                passwords.encode(password), AppUser.Type.PASSENGER, AppUser.Status.ACTIVE);
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Trade-off: 409 reveals that the email/phone is registered. A real deployment moves to
            // email-verification-first ("check your inbox" for both cases); the gateway's per-IP rate
            // limit (Phase 10) bounds enumeration until then.
            throw new IdentityException("ACCOUNT_EXISTS", HttpStatus.CONFLICT, "an account with these details exists");
        }
        roles.save(new UserRole(id, "PASSENGER", ScopeGrant.ScopeType.GLOBAL, "*", id));
        audit.record(id.toString(), "REGISTER", "User", id.toString(), Map.of("type", "PASSENGER"), ip);
        return id;
    }

    /**
     * Not {@code noRollbackFor}-annotated by accident: a failed login must still COMMIT the incremented
     * failure counter, so failures return normally inside the transaction and the controller turns the
     * result into a 401. Throwing here would roll back the lockout bookkeeping.
     */
    @Transactional
    public LoginOutcome login(String email, String password, String otp, String userAgent, String ip) {
        Instant now = Instant.now();
        AppUser user = users.lockByEmail(normalizeEmail(email)).orElse(null);
        if (user == null) {
            passwords.matches(password, dummyHash);
            return fail(null, "UNKNOWN_USER", ip);
        }
        if (user.isLocked(now) || user.getStatus() == AppUser.Status.DISABLED || user.getStatus() == AppUser.Status.ERASED) {
            passwords.matches(password, dummyHash);
            return fail(user, "LOCKED_OR_DISABLED", ip);
        }
        if (!passwords.matches(password, user.getPasswordHash())) {
            user.recordFailedLogin(now);
            return fail(user, "BAD_PASSWORD", ip);
        }
        if (passwords.upgradeEncoding(user.getPasswordHash())) {
            user.rehashPassword(passwords.encode(password)); // transparent upgrade when encoder params change
        }

        if (user.getStatus() == AppUser.Status.PENDING_MFA) {
            user.recordSuccessfulLogin(now);
            audit.record(user.getId().toString(), "LOGIN_MFA_ENROLMENT_REQUIRED", "User", user.getId().toString(), Map.of(), ip);
            return LoginOutcome.ok(new LoginResult(null, tokens.issueEnrolmentToken(user)));
        }

        List<String> amr = List.of("pwd");
        if (user.requiresMfa()) {
            String secret = cipher.decrypt(user.getMfaSecretEnc(), AAD_MFA);
            OptionalLong step = Totp.verify(secret, otp, user.getMfaLastStep(), now);
            if (step.isEmpty()) {
                user.recordFailedLogin(now); // OTP failures count toward lockout too, or OTP is brute-forceable
                return fail(user, otp == null ? "OTP_MISSING" : "BAD_OTP", ip);
            }
            user.acceptTotpStep(step.getAsLong());
            amr = List.of("pwd", "otp");
        }
        user.recordSuccessfulLogin(now);
        IssuedTokens session = tokens.issueSession(user, amr, userAgent, ip);
        audit.record(user.getId().toString(), "LOGIN", "User", user.getId().toString(), Map.of("amr", amr), ip);
        meters.counter("identity.login", "result", "success", "reason", "none").increment();   // same tag KEYS as failures, or Prometheus drops one series (Phase 12)
        return LoginOutcome.ok(new LoginResult(session, null));
    }

    public record LoginOutcome(LoginResult result) {
        static LoginOutcome ok(LoginResult r) {
            return new LoginOutcome(r);
        }

        public boolean failed() {
            return result == null;
        }
    }

    private LoginOutcome fail(AppUser user, String reason, String ip) {
        meters.counter("identity.login", "result", "failure", "reason", reason).increment();
        String actor = user == null ? "anonymous" : user.getId().toString();
        audit.record(actor, "LOGIN_FAILED", "User", actor, Map.of("reason", reason), ip);
        return new LoginOutcome(null);
    }

    /**
     * Rotation with reuse detection. Presenting a token that was already rotated means either a race
     * between tabs (within {@link #ROTATION_GRACE}) or a stolen token being replayed: in the latter case
     * the whole family is revoked, logging out both the thief and the victim, who then re-authenticates.
     */
    @Transactional(noRollbackFor = IdentityException.class)
    public IssuedTokens refresh(String rawToken, String userAgent, String ip) {
        Instant now = Instant.now();
        if (rawToken == null || rawToken.length() < 40) {
            throw IdentityException.authFailed();
        }
        RefreshToken current = refreshTokens.lockByHash(TokenService.hash(rawToken))
                .orElseThrow(IdentityException::authFailed);
        if (current.isRevoked() || current.isExpired(now)) {
            throw IdentityException.authFailed();
        }
        if (current.isReplaced()) {
            if (current.replacedWithin(ROTATION_GRACE, now)) {
                log.info("refresh token reused within grace window (family {}); treating as concurrent refresh",
                        current.getFamilyId());
            } else {
                refreshTokens.revokeFamily(current.getFamilyId(), now);
                audit.record(current.getUserId().toString(), "REFRESH_TOKEN_REUSE", "TokenFamily",
                        current.getFamilyId().toString(), Map.of("action", "family revoked"), ip);
                meters.counter("identity.refresh.reuse_detected").increment();
                throw IdentityException.authFailed(); // commits the revocation (noRollbackFor)
            }
        }
        AppUser user = users.findById(current.getUserId()).orElseThrow(IdentityException::authFailed);
        if (user.getStatus() != AppUser.Status.ACTIVE) {
            refreshTokens.revokeFamily(current.getFamilyId(), now);
            throw IdentityException.authFailed();
        }
        // AMR carries forward: the session was established with these factors.
        List<String> amr = user.requiresMfa() ? List.of("pwd", "otp") : List.of("pwd");
        IssuedTokens next = tokens.issueRotation(user, amr, current.getFamilyId(), userAgent, ip);
        if (!current.isReplaced()) {
            current.markReplaced(next.refreshTokenId(), now);
        }
        return next;
    }

    /**
     * Phase 4D. Any signed-in user (passenger or staff). A wrong current password counts toward the same
     * lockout as login, so a stolen 10-minute access token can't be used to guess the password; that's why
     * failures commit ({@code noRollbackFor}). Every session is revoked afterwards, including this one.
     */
    @Transactional(noRollbackFor = IdentityException.class)
    public void changePassword(UUID userId, String currentPassword, String newPassword, String ip) {
        AppUser user = verifiedForAccountChange(userId, currentPassword, ip);
        policy.check(newPassword, user.getEmail());
        if (passwords.matches(newPassword, user.getPasswordHash())) {
            throw new IdentityException("PASSWORD_REUSED", HttpStatus.UNPROCESSABLE_ENTITY,
                    "the new password must differ from the current one");
        }
        user.rehashPassword(passwords.encode(newPassword));
        int revoked = refreshTokens.revokeAllForUser(userId, Instant.now());
        audit.record(userId.toString(), "PASSWORD_CHANGED", "User", userId.toString(), Map.of("sessionsRevoked", revoked), ip);
    }

    /**
     * Phase 4D: a passenger closes their own account. Staff accounts are closed by an admin instead, so a
     * stolen staff session can't destroy the account trail. Bookings and payments keep the user id (they are
     * financial records); the person's email, phone, MFA secret, roles and sessions go.
     */
    @Transactional(noRollbackFor = IdentityException.class)
    public void eraseSelf(UUID userId, String password, String ip) {
        AppUser user = verifiedForAccountChange(userId, password, ip);
        if (user.getUserType() != AppUser.Type.PASSENGER) {
            throw new IdentityException("ERASE_NOT_ALLOWED", HttpStatus.FORBIDDEN,
                    "staff accounts are closed by an administrator");
        }
        user.erase(passwords.encode("erased-" + UUID.randomUUID() + UUID.randomUUID()));
        users.saveAndFlush(user);
        roles.deleteAll(roles.findByUserId(userId));
        refreshTokens.revokeAllForUser(userId, Instant.now());
        audit.record(userId.toString(), "ACCOUNT_ERASED", "User", userId.toString(), Map.of("by", "self"), ip);
    }

    private AppUser verifiedForAccountChange(UUID userId, String password, String ip) {
        Instant now = Instant.now();
        AppUser user = users.lockById(userId).orElseThrow(IdentityException::authFailed);
        if (user.getStatus() != AppUser.Status.ACTIVE || user.isLocked(now)) {
            passwords.matches(password, dummyHash);
            audit.record(userId.toString(), "ACCOUNT_CHANGE_REFUSED", "User", userId.toString(), Map.of("reason", "LOCKED_OR_INACTIVE"), ip);
            throw IdentityException.authFailed();
        }
        if (!passwords.matches(password, user.getPasswordHash())) {
            user.recordFailedLogin(now);
            audit.record(userId.toString(), "ACCOUNT_CHANGE_REFUSED", "User", userId.toString(), Map.of("reason", "BAD_PASSWORD"), ip);
            throw IdentityException.authFailed();
        }
        return user;
    }

    @Transactional
    public void logout(String rawToken, String ip) {
        if (rawToken == null) {
            return;
        }
        refreshTokens.lockByHash(TokenService.hash(rawToken)).ifPresent(t -> {
            refreshTokens.revokeFamily(t.getFamilyId(), Instant.now());
            audit.record(t.getUserId().toString(), "LOGOUT", "TokenFamily", t.getFamilyId().toString(), Map.of(), ip);
        });
    }
}
