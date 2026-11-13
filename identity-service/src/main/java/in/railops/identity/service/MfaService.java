package in.railops.identity.service;

import in.railops.common.crypto.FieldCipher;
import in.railops.identity.crypto.Totp;
import in.railops.identity.domain.AppUser;
import in.railops.identity.domain.Repositories.AppUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Staff MFA enrolment: begin (secret shown once) -> confirm with a valid code -> account ACTIVE.
 * Only reachable with the 5-minute enrolment token issued after a correct password.
 */
@Service
public class MfaService {

    public record Enrolment(String secretBase32, String otpauthUri) {
    }

    private final AppUserRepository users;
    private final FieldCipher cipher;
    private final AuditService audit;

    public MfaService(AppUserRepository users, FieldCipher cipher, AuditService audit) {
        this.users = users;
        this.cipher = cipher;
        this.audit = audit;
    }

    @Transactional
    public Enrolment begin(UUID userId) {
        AppUser user = users.lockById(userId).orElseThrow(IdentityException::authFailed);
        if (user.getStatus() != AppUser.Status.PENDING_MFA) {
            throw new IdentityException("MFA_ALREADY_ACTIVE", HttpStatus.CONFLICT, "MFA is already enrolled");
        }
        String secret = Totp.newSecretBase32();
        user.beginMfaEnrolment(cipher.encrypt(secret, AuthService.AAD_MFA));
        audit.record(userId.toString(), "MFA_ENROLMENT_STARTED", "User", userId.toString(), Map.of(), null);
        return new Enrolment(secret, Totp.otpauthUri("RailOps", user.getEmail(), secret));
    }

    @Transactional
    public void confirm(UUID userId, String code) {
        AppUser user = users.lockById(userId).orElseThrow(IdentityException::authFailed);
        if (user.getStatus() != AppUser.Status.PENDING_MFA || user.getMfaSecretEnc() == null) {
            throw new IdentityException("MFA_NOT_PENDING", HttpStatus.CONFLICT, "no enrolment in progress");
        }
        String secret = cipher.decrypt(user.getMfaSecretEnc(), AuthService.AAD_MFA);
        OptionalLong step = Totp.verify(secret, code, user.getMfaLastStep(), Instant.now());
        if (step.isEmpty()) {
            throw new IdentityException("MFA_CODE_INVALID", HttpStatus.UNPROCESSABLE_ENTITY, "code is not valid");
        }
        user.activateWithMfa(step.getAsLong());
        audit.record(userId.toString(), "MFA_ENROLLED", "User", userId.toString(), Map.of(), null);
    }
}
