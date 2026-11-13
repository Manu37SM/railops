package in.railops.common.security;

/**
 * The access-token contract between identity-service (issuer) and every resource server.
 *
 * <pre>
 * iss  https://identity.railops.local      aud  ["railops-api"]  (enrolment tokens: ["railops-mfa"])
 * sub  user UUID                            utyp PASSENGER | AGENT | STAFF
 * roles ["STATION_MASTER", ...]             grants ["STATION_MASTER:STATION:CSMT", "CONTROLLER:DIVISION:BCT"]
 * amr  ["pwd"] or ["pwd","otp"]             jti  unique id (for deny-listing if ever needed)
 * </pre>
 *
 * No PII (name, email, phone) is ever placed in a token: tokens end up in logs, browser storage
 * and proxies.
 */
public final class RailopsClaims {

    public static final String AUDIENCE_API = "railops-api";
    public static final String AUDIENCE_MFA = "railops-mfa";
    public static final String AUDIENCE_INTERNAL = "railops-internal";
    public static final String USER_TYPE = "utyp";
    public static final String ROLES = "roles";
    public static final String GRANTS = "grants";
    public static final String AMR = "amr";
    /** The account's email, on access tokens only (Phase 4A): lets RailLens find the same person. */
    public static final String EMAIL = "email";

    private RailopsClaims() {
    }
}
