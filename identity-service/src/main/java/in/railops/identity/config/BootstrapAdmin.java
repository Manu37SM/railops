package in.railops.identity.config;

import in.railops.common.security.ScopeGrant;
import in.railops.identity.domain.AppUser;
import in.railops.identity.domain.Repositories.AppUserRepository;
import in.railops.identity.domain.Repositories.UserRoleRepository;
import in.railops.identity.domain.UserRole;
import in.railops.identity.service.AuditService;
import in.railops.identity.service.AuthService;
import in.railops.identity.service.PasswordPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Creates the very first ADMIN, once, from deployment secrets (never logged). The account starts in
 * PENDING_MFA, so even a leaked bootstrap password can't reach admin APIs without enrolling MFA, and
 * enrolment is a visible, audited event. Does nothing if any ADMIN grant already exists.
 */
@Component
public class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final AppUserRepository users;
    private final UserRoleRepository roles;
    private final PasswordEncoder passwords;
    private final PasswordPolicy policy;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final String email;
    private final String password;

    public BootstrapAdmin(AppUserRepository users, UserRoleRepository roles, PasswordEncoder passwords,
                          PasswordPolicy policy, AuditService audit, JdbcClient jdbc,
                          @Value("${railops.bootstrap.admin-email:}") String email,
                          @Value("${railops.bootstrap.admin-password:}") String password) {
        this.users = users;
        this.roles = roles;
        this.passwords = passwords;
        this.policy = policy;
        this.audit = audit;
        this.jdbc = jdbc;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) {
            return;
        }
        // Serialize concurrent replicas starting at once.
        jdbc.sql("SELECT pg_advisory_xact_lock(42)").query(Object.class).single();
        Integer admins = jdbc.sql("SELECT count(*) FROM identity.user_role WHERE role_code = 'ADMIN'")
                .query(Integer.class).single();
        if (admins > 0) {
            return;
        }
        policy.check(password, email);
        UUID id = UUID.randomUUID();
        users.save(new AppUser(id, AuthService.normalizeEmail(email), null, null, passwords.encode(password),
                AppUser.Type.STAFF, AppUser.Status.PENDING_MFA));
        users.flush();
        roles.save(new UserRole(id, "ADMIN", ScopeGrant.ScopeType.GLOBAL, "*", id));
        audit.record("system", "BOOTSTRAP_ADMIN", "User", id.toString(), Map.of(), null);
        log.warn("Bootstrap ADMIN created (id {}); it must enrol MFA on first login. Remove the bootstrap secrets now.", id);
    }
}
