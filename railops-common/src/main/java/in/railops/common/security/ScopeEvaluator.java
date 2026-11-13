package in.railops.common.security;

import in.railops.common.security.ScopeGrant.StationPlace;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * ABAC check: does the caller hold one of {@code roles} with a scope that covers {@code place}?
 * Registered as bean "scopes" so it can be used in {@code @PreAuthorize}.
 */
public class ScopeEvaluator {

    public boolean covers(Authentication auth, StationPlace place, Collection<String> roles) {
        return grants(auth).stream().anyMatch(g -> roles.contains(g.role()) && g.covers(place));
    }

    public boolean coversAny(Authentication auth, Collection<StationPlace> places, Collection<String> roles) {
        return places.stream().anyMatch(p -> covers(auth, p, roles));
    }

    public List<ScopeGrant> grants(Authentication auth) {
        if (!(auth instanceof JwtAuthenticationToken jwtAuth) || !auth.isAuthenticated()) {
            return List.of();
        }
        Set<String> heldRoles = Set.copyOf(RailopsJwtAuthenticationConverter.list(jwtAuth.getToken(), RailopsClaims.ROLES));
        return RailopsJwtAuthenticationConverter.list(jwtAuth.getToken(), RailopsClaims.GRANTS).stream()
                .map(ScopeGrant::parse)
                .flatMap(Optional::stream)
                // A grant for a role the token doesn't also list is inconsistent: ignore it.
                .filter(g -> heldRoles.contains(g.role()))
                .toList();
    }
}
