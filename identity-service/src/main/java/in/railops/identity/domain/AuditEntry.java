package in.railops.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log", schema = "identity")
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "source_event_id")
    private UUID sourceEventId;
    @Column(name = "actor_id", nullable = false)
    private String actorId;
    @Column(nullable = false)
    private String action;
    @Column(name = "target_type", nullable = false)
    private String targetType;
    @Column(name = "target_id", nullable = false)
    private String targetId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String detail;
    private String ip;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
    @Column(name = "prev_hash", nullable = false)
    private byte[] prevHash;
    @Column(nullable = false)
    private byte[] hash;

    protected AuditEntry() {
    }

    public AuditEntry(UUID sourceEventId, String actorId, String action, String targetType, String targetId,
                      String detailJson, String ip, Instant occurredAt, byte[] prevHash, byte[] hash) {
        this.sourceEventId = sourceEventId;
        this.actorId = actorId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.detail = detailJson;
        this.ip = ip;
        this.occurredAt = occurredAt;
        this.prevHash = prevHash;
        this.hash = hash;
    }

    public Long getId() { return id; }
    public String getActorId() { return actorId; }
    public String getAction() { return action; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public String getDetail() { return detail; }
    public String getIp() { return ip; }
    public Instant getOccurredAt() { return occurredAt; }
    public byte[] getPrevHash() { return prevHash; }
    public byte[] getHash() { return hash; }
}
