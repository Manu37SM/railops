package in.railops.common.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;

import java.util.List;
import java.util.Set;

/** Issuer + expiry/nbf (60s skew) + audience + subject present. */
public final class RailopsJwtValidators {

    private RailopsJwtValidators() {
    }

    public static OAuth2TokenValidator<Jwt> forAudiences(String issuer, Set<String> acceptedAudiences) {
        OAuth2TokenValidator<Jwt> audience = jwt -> {
            List<String> aud = jwt.getAudience();
            boolean ok = aud != null && aud.stream().anyMatch(acceptedAudiences::contains);
            return ok ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "audience not accepted", null));
        };
        OAuth2TokenValidator<Jwt> subject = new JwtClaimValidator<String>("sub", s -> s != null && !s.isBlank());
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audience, subject);
    }
}
