package in.railops.identity.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class Repositories {

    private Repositories() {
    }

    public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

        /** Row lock so concurrent wrong-password attempts can't each read failed_logins=4 and skip the lockout. */
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select u from AppUser u where u.email = :email")
        Optional<AppUser> lockByEmail(@Param("email") String email);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select u from AppUser u where u.id = :id")
        Optional<AppUser> lockById(@Param("id") UUID id);

        boolean existsByEmail(String email);
    }

    public interface UserRoleRepository extends JpaRepository<UserRole, Long> {

        List<UserRole> findByUserId(UUID userId);
    }

    public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select t from RefreshToken t where t.tokenHash = :hash")
        Optional<RefreshToken> lockByHash(@Param("hash") byte[] hash);

        @Modifying
        @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :family and t.revokedAt is null")
        int revokeFamily(@Param("family") UUID family, @Param("now") Instant now);

        @Modifying
        @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :user and t.revokedAt is null")
        int revokeAllForUser(@Param("user") UUID user, @Param("now") Instant now);
    }

    public interface AuditEntryRepository extends JpaRepository<AuditEntry, Long> {

        Optional<AuditEntry> findTopByOrderByIdDesc();

        List<AuditEntry> findByIdGreaterThanOrderByIdAsc(Long afterId, org.springframework.data.domain.Pageable page);

        boolean existsBySourceEventId(UUID sourceEventId);
    }
}
