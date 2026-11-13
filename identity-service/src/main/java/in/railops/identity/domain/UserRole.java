package in.railops.identity.domain;

import in.railops.common.security.ScopeGrant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_role", schema = "identity")
public class UserRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "role_code", nullable = false)
    private String roleCode;
    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false)
    private ScopeGrant.ScopeType scopeType;
    @Column(name = "scope_value", nullable = false)
    private String scopeValue;
    @Column(name = "granted_by", nullable = false)
    private UUID grantedBy;
    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    protected UserRole() {
    }

    public UserRole(UUID userId, String roleCode, ScopeGrant.ScopeType scopeType, String scopeValue, UUID grantedBy) {
        this.userId = userId;
        this.roleCode = roleCode;
        this.scopeType = scopeType;
        this.scopeValue = scopeValue;
        this.grantedBy = grantedBy;
        this.grantedAt = Instant.now();
    }

    public ScopeGrant toGrant() {
        return new ScopeGrant(roleCode, scopeType, scopeValue);
    }

    public Long getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getRoleCode() { return roleCode; }
}
