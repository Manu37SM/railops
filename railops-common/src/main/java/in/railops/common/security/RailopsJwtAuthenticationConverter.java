package in.railops.common.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Maps {@code roles} to {@code ROLE_*} authorities and {@code amr} to {@code AMR_*} (so endpoints can
 * demand MFA). Scoped grants stay in the token and are checked by {@link ScopeEvaluator}, because a flat
 * authority can't say "station master, but only at CSMT".
 */
public class RailopsJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final Pattern SAFE = Pattern.compile("[A-Z_]{2,40}");

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String role : list(jwt, RailopsClaims.ROLES)) {
            if (SAFE.matcher(role).matches()) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
        }
        for (String m : list(jwt, RailopsClaims.AMR)) {
            if (m.matches("[a-z]{2,10}")) {
                authorities.add(new SimpleGrantedAuthority("AMR_" + m));
            }
        }
        // OAuth2 "scope" (space-separated) on service tokens -> SCOPE_x, as Spring's own converter would map it.
        String scope = jwt.getClaimAsString("scope");
        if (scope != null) {
            for (String sc : scope.trim().split("\\s+")) {
                if (sc.matches("[a-z]+:[a-z]+")) {
                    authorities.add(new SimpleGrantedAuthority("SCOPE_" + sc));
                }
            }
        }
        if (jwt.getAudience() != null && jwt.getAudience().contains(RailopsClaims.AUDIENCE_MFA)) {
            authorities.add(new SimpleGrantedAuthority("SCOPE_mfa_enroll"));
        }
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    static List<String> list(Jwt jwt, String claim) {
        List<String> v = jwt.getClaimAsStringList(claim);
        return v == null ? List.of() : v;
    }
}
