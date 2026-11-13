package in.railops.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_user", schema = "identity")
public class AppUser {

    public enum Type { PASSENGER, STAFF, AGENT }

    public enum Status { ACTIVE, PENDING_MFA, DISABLED, ERASED }

    public static final int MAX_FAILED_LOGINS = 5;
    public static final Duration LOCKOUT = Duration.ofMinutes(15);

    @Id
    private UUID id;
    private String email;
    @Column(name = "phone_hmac")
    private byte[] phoneHmac;
    @Column(name = "phone_enc")
    private byte[] phoneEnc;
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;
    @Enumerated(EnumType.STRING)
    @Column(name = "user_type", nullable = false)
    private Type userType;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    @Column(name = "mfa_secret_enc")
    private byte[] mfaSecretEnc;
    @Column(name = "mfa_last_step")
    private long mfaLastStep;
    @Column(name = "failed_logins")
    private short failedLogins;
    @Column(name = "locked_until")
    private Instant lockedUntil;
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Version
    private int version;

    protected AppUser() {
    }

    public AppUser(UUID id, String email, byte[] phoneHmac, byte[] phoneEnc, String passwordHash, Type type,
                   Status status) {
        this.id = id;
        this.email = email;
        this.phoneHmac = phoneHmac;
        this.phoneEnc = phoneEnc;
        this.passwordHash = passwordHash;
        this.userType = type;
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** Counts a failure; locks the account after {@link #MAX_FAILED_LOGINS} consecutive failures. */
    public void recordFailedLogin(Instant now) {
        failedLogins++;
        if (failedLogins >= MAX_FAILED_LOGINS) {
            lockedUntil = now.plus(LOCKOUT);
            failedLogins = 0;
        }
        updatedAt = now;
    }

    public void recordSuccessfulLogin(Instant now) {
        failedLogins = 0;
        lockedUntil = null;
        updatedAt = now;
    }

    public boolean requiresMfa() {
        return userType != Type.PASSENGER || mfaSecretEnc != null;
    }

    public void beginMfaEnrolment(byte[] encryptedSecret) {
        if (status != Status.PENDING_MFA) {
            throw new IllegalStateException("MFA enrolment only allowed while PENDING_MFA");
        }
        this.mfaSecretEnc = encryptedSecret;
        this.updatedAt = Instant.now();
    }

    public void activateWithMfa(long step) {
        if (status != Status.PENDING_MFA || mfaSecretEnc == null) {
            throw new IllegalStateException("no MFA enrolment in progress");
        }
        this.status = Status.ACTIVE;
        this.mfaLastStep = step;
        this.updatedAt = Instant.now();
    }

    public void acceptTotpStep(long step) {
        if (step <= mfaLastStep) {
            throw new IllegalStateException("TOTP step already used");
        }
        this.mfaLastStep = step;
    }

    public void rehashPassword(String newHash) {
        this.passwordHash = newHash;
        this.updatedAt = Instant.now();
    }

    /**
     * Self-service erasure (Phase 4D). The row stays as a tombstone because bookings, payments and the audit
     * chain reference the id; everything that identifies the person goes. {@code unusableHash} is a hash of a
     * random secret nobody knows, so the NOT NULL column holds nothing that can ever match.
     */
    public void erase(String unusableHash) {
        this.email = null;
        this.phoneHmac = null;
        this.phoneEnc = null;
        this.mfaSecretEnc = null;
        this.passwordHash = unusableHash;
        this.status = Status.ERASED;
        this.failedLogins = 0;
        this.lockedUntil = null;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Type getUserType() { return userType; }
    public Status getStatus() { return status; }
    public byte[] getMfaSecretEnc() { return mfaSecretEnc; }
    public long getMfaLastStep() { return mfaLastStep; }
}
