package in.railops.reservation.security;

import in.railops.common.security.ScopeEvaluator;
import in.railops.common.security.ScopeGrant;
import in.railops.common.security.ScopeGrant.StationPlace;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * "Can this staff member see this run?" — true when one of their scoped grants covers any station the
 * run calls at. A Pune station master sees trains through Pune; a Mumbai-division controller sees every
 * run touching a BCT-division station; a zonal or GLOBAL grant widens accordingly.
 *
 * <p>Stations missing from station_ref match only GLOBAL grants (fail closed).
 */
@Component("runAccess")
public class RunAccessGuard {

    private final JdbcClient jdbc;
    private final ScopeEvaluator scopes;

    public RunAccessGuard(JdbcClient jdbc, ScopeEvaluator scopes) {
        this.jdbc = jdbc;
        this.scopes = scopes;
    }

    public boolean canView(Authentication auth, long runId) {
        return covers(auth, runId, Set.of("STATION_MASTER", "CONTROLLER", "ADMIN", "AUDITOR"));
    }

    /** Operational changes (charting): controllers in scope, or admins. Station masters and auditors only view. */
    public boolean canOperate(Authentication auth, long runId) {
        return covers(auth, runId, Set.of("CONTROLLER", "ADMIN"));
    }

    /**
     * Creating runs (timetable import): only admins with a GLOBAL grant. A new run has no stations in anyone's scope
     * yet, and it decides seats nationwide, so station/division scoping can't apply.
     */
    public boolean canPlanRuns(Authentication auth) {
        return scopes.grants(auth).stream()
                .anyMatch(g -> "ADMIN".equals(g.role()) && g.scopeType() == ScopeGrant.ScopeType.GLOBAL);
    }

    private boolean covers(Authentication auth, long runId, Set<String> roles) {
        List<StationPlace> places = jdbc.sql("""
                        SELECT s.station_code, r.division_code, r.zone_code
                        FROM reservation.run_stop s
                        LEFT JOIN reservation.station_ref r ON r.code = s.station_code
                        WHERE s.train_run_id = :run
                        """)
                .param("run", runId)
                .query((rs, n) -> new StationPlace(rs.getString(1),
                        rs.getString(2) == null ? "?" : rs.getString(2),
                        rs.getString(3) == null ? "?" : rs.getString(3)))
                .list();
        return scopes.coversAny(auth, places, roles);
    }
}
