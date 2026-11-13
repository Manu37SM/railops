package in.railops.common.security;

import java.util.Optional;

/**
 * A role held within a scope, e.g. STATION_MASTER at STATION CSMT. Encoded in tokens as
 * {@code ROLE:SCOPE_TYPE:SCOPE_VALUE}; GLOBAL grants use value "*".
 */
public record ScopeGrant(String role, ScopeType scopeType, String scopeValue) {

    public enum ScopeType { GLOBAL, ZONE, DIVISION, STATION }

    public String encode() {
        return role + ":" + scopeType + ":" + scopeValue;
    }

    /** Lenient parse: a malformed grant is ignored (denies), never interpreted generously. */
    public static Optional<ScopeGrant> parse(String encoded) {
        if (encoded == null) {
            return Optional.empty();
        }
        String[] p = encoded.split(":", -1);
        if (p.length != 3 || p[0].isBlank() || p[2].isBlank()) {
            return Optional.empty();
        }
        try {
            ScopeType type = ScopeType.valueOf(p[1]);
            if (type == ScopeType.GLOBAL && !"*".equals(p[2])) {
                return Optional.empty();
            }
            return Optional.of(new ScopeGrant(p[0], type, p[2]));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** True if this grant covers a place with the given station/division/zone. */
    public boolean covers(StationPlace place) {
        return switch (scopeType) {
            case GLOBAL -> true;
            case ZONE -> scopeValue.equals(place.zone());
            case DIVISION -> scopeValue.equals(place.division());
            case STATION -> scopeValue.equals(place.station());
        };
    }

    public record StationPlace(String station, String division, String zone) {
    }
}
