package in.railops.identity.raillens;

import in.railops.common.security.ScopeGrant;
import in.railops.common.web.PiiMasker;
import in.railops.identity.domain.AppUser;
import in.railops.identity.domain.Repositories.AppUserRepository;
import in.railops.identity.domain.Repositories.UserRoleRepository;
import in.railops.identity.domain.UserRole;
import in.railops.identity.service.AuditService;
import in.railops.identity.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Moves RailLens accounts into this login (single login, Phase 4C). Started by a RailOps admin; uses that admin's
 * token to read RailLens' export and to send back the links.
 *
 * <p>Each account: created with the same email and the same bcrypt password hash (stored as {@code {bcrypt}...},
 * which this service already checks, and upgrades to Argon2 at the person's next login), so nobody's password
 * changes. Passenger role only: RailLens admins are listed for a person to grant RailOps roles deliberately, never
 * granted automatically. An email that already has a RailOps account is reported, never merged.
 *
 * <p>Safe to repeat: {@code identity.raillens_link} remembers what was moved, and RailLens only exports accounts
 * that are not linked yet. Each account is its own transaction.
 */
@Service
public class AccountMoveService {

    private static final Logger log = LoggerFactory.getLogger(AccountMoveService.class);
    static final int PAGE = 200;
    static final int MAX_PAGES = 1000;
    static final int MAX_LISTED = 50;
    private static final Pattern BCRYPT = Pattern.compile("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,189}$");

    enum Outcome { CREATED, ALREADY_MOVED, EMAIL_TAKEN, INVALID }

    record Moved(Outcome outcome, UUID userId) {
    }

    public record Summary(int examined, int created, int alreadyMoved, int linkedInRailLens, int emailTaken,
                          List<String> emailTakenSample, List<Long> invalid, List<Long> raillensAdmins,
                          List<Long> linkConflicts) {
    }

    private final RailLensAccounts raillens;
    private final AppUserRepository users;
    private final UserRoleRepository roles;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public AccountMoveService(RailLensAccounts raillens, AppUserRepository users, UserRoleRepository roles,
                              AuditService audit, JdbcClient jdbc, PlatformTransactionManager txm) {
        this.raillens = raillens;
        this.users = users;
        this.roles = roles;
        this.audit = audit;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Summary move(UUID actor, String adminBearer, String ip) {
        int examined = 0, created = 0, already = 0, linked = 0, taken = 0;
        List<String> takenSample = new ArrayList<>();
        List<Long> invalid = new ArrayList<>();
        List<Long> admins = new ArrayList<>();
        List<Long> conflicts = new ArrayList<>();
        long after = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<RailLensAccounts.Pending> batch = raillens.pending(after, PAGE, adminBearer);
            if (batch.isEmpty()) {
                break;
            }
            List<RailLensAccounts.Link> links = new ArrayList<>();
            for (RailLensAccounts.Pending p : batch) {
                after = Math.max(after, p.raillensUserId());
                examined++;
                Moved m = moveOne(p, actor, ip);
                switch (m.outcome()) {
                    case CREATED -> created++;
                    case ALREADY_MOVED -> already++;
                    case EMAIL_TAKEN -> {
                        taken++;
                        if (takenSample.size() < MAX_LISTED) {
                            takenSample.add(p.raillensUserId() + " " + PiiMasker.mask(p.email()));
                        }
                    }
                    case INVALID -> addCapped(invalid, p.raillensUserId());
                }
                if (m.userId() != null) {
                    links.add(new RailLensAccounts.Link(p.raillensUserId(), m.userId()));
                    if (p.admin()) {
                        addCapped(admins, p.raillensUserId());
                    }
                }
            }
            if (!links.isEmpty()) {
                RailLensAccounts.LinkResult r = raillens.link(links, adminBearer);
                linked += r.linked() + r.alreadyLinked();
                r.conflicts().forEach(c -> addCapped(conflicts, c));
            }
        }
        Summary s = new Summary(examined, created, already, linked, taken, takenSample, invalid, admins, conflicts);
        audit.record(actor.toString(), "RAILLENS_ACCOUNT_MOVE", "System", "raillens",
                Map.of("examined", examined, "created", created, "alreadyMoved", already, "emailTaken", taken), ip);
        log.info("RailLens account move by {}: {}", actor, s.examined() + " examined, " + created + " created, "
                + already + " already moved, " + taken + " email taken, " + invalid.size() + " invalid");
        return s;
    }

    Moved moveOne(RailLensAccounts.Pending p, UUID actor, String ip) {
        Optional<UUID> existing = linkedUser(p.raillensUserId());
        if (existing.isPresent()) {
            return new Moved(Outcome.ALREADY_MOVED, existing.get());
        }
        if (p.passwordHash() == null || !BCRYPT.matcher(p.passwordHash()).matches()
                || p.email() == null || !EMAIL.matcher(p.email().trim()).matches()) {
            return new Moved(Outcome.INVALID, null);
        }
        String email = AuthService.normalizeEmail(p.email());
        if (users.existsByEmail(email)) {
            return new Moved(Outcome.EMAIL_TAKEN, null);
        }
        try {
            UUID id = tx.execute(status -> {
                UUID newId = UUID.randomUUID();
                users.saveAndFlush(new AppUser(newId, email, null, null, "{bcrypt}" + p.passwordHash(),
                        AppUser.Type.PASSENGER, AppUser.Status.ACTIVE));
                roles.save(new UserRole(newId, "PASSENGER", ScopeGrant.ScopeType.GLOBAL, "*", actor));
                jdbc.sql("INSERT INTO identity.raillens_link (raillens_user_id, user_id) VALUES (:r, :u)")
                        .param("r", p.raillensUserId()).param("u", newId).update();
                audit.record(actor.toString(), "IMPORT_RAILLENS_ACCOUNT", "User", newId.toString(),
                        Map.of("raillensUserId", p.raillensUserId()), ip);
                return newId;
            });
            return new Moved(Outcome.CREATED, id);
        } catch (DataIntegrityViolationException raced) {
            // Same email registered, or the same account moved, at this very moment.
            return linkedUser(p.raillensUserId()).map(u -> new Moved(Outcome.ALREADY_MOVED, u))
                    .orElse(new Moved(Outcome.EMAIL_TAKEN, null));
        }
    }

    private Optional<UUID> linkedUser(long raillensUserId) {
        return jdbc.sql("SELECT user_id FROM identity.raillens_link WHERE raillens_user_id = :r")
                .param("r", raillensUserId).query(UUID.class).optional();
    }

    private static void addCapped(List<Long> list, long value) {
        if (list.size() < MAX_LISTED) {
            list.add(value);
        }
    }
}
