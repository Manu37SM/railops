package in.railops.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One link in a rotation chain. Only the SHA-256 of the token is stored: a database leak doesn't
 * hand out live sessions.
 */
@Entity
@Table(name = "refresh_token", schema = "identity")
public class RefreshToken {

    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "family_id", nullable = false)
    private UUID familyId;
    @Column(name = "token_hash", nullable = false)
    private byte[] tokenHash;
    @Column(name = "replaced_by")
    private UUID replacedBy;
    @Column(name = "replaced_at")
    private Instant replacedAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "user_agent")
    private String userAgent;
    private String ip;

    protected RefreshToken() {
    }

    public RefreshToken(UUID id, UUID userId, UUID familyId, byte[] tokenHash, Instant expiresAt,
                        String userAgent, String ip) {
        this.id = id;
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
        this.userAgent = userAgent == null ? null : userAgent.substring(0, Math.min(200, userAgent.length()));
        this.ip = ip;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isReplaced() {
        return replacedBy != null;
    }

    /** A replaced token presented again within this window is treated as a concurrent-tab race, not theft. */
    public boolean replacedWithin(Duration grace, Instant now) {
        return replacedAt != null && replacedAt.plus(grace).isAfter(now);
    }

    public void markReplaced(UUID successor, Instant now) {
        this.replacedBy = successor;
        this.replacedAt = now;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getFamilyId() { return familyId; }
    public UUID getReplacedBy() { return replacedBy; }
}
