package in.railops.identity.web;

import in.railops.identity.service.AuthService;
import in.railops.identity.service.AuthService.LoginOutcome;
import in.railops.identity.service.IdentityException;
import in.railops.identity.service.TokenService.IssuedTokens;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Sessions for clients that are not this site's browser pages (Phase 4A): the RailLens Android app (Keychain) and
 * the RailLens website's own server, which keeps the token in a first-party HttpOnly cookie on its domain because a
 * cookie set by RailOps would be a blocked third-party cookie there.
 *
 * <p>Same accounts, rules, lockout, MFA and rotation as {@link AuthController}; the only difference is that the
 * refresh token travels in the JSON body instead of a cookie. With no cookie involved there is no ambient
 * credential for another site to ride, so no CSRF header is needed. The browser endpoints are unchanged.
 */
@RestController
@Tag(name = "Auth (apps)", description = "Login/refresh/logout with the refresh token in the body, for mobile apps and server-side clients")
@RequestMapping("/api/v1/auth/app")
public class AppAuthController {

    public record AppSession(String accessToken, String tokenType, long expiresIn, String refreshToken, long refreshExpiresIn) {
    }

    public record AppRefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {
    }

    private final AuthService auth;

    public AppAuthController(AuthService auth) {
        this.auth = auth;
    }

    @Operation(summary = "Log in (app)", description = "Returns access and refresh tokens in the body, or "
            + "MFA_ENROLMENT_REQUIRED with a 5-minute enrolment token. Staff must send otp. All failures are an identical 401.")
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody AuthController.LoginRequest r, HttpServletRequest req) {
        LoginOutcome outcome = auth.login(r.email(), r.password(), r.otp(), req.getHeader(HttpHeaders.USER_AGENT),
                req.getRemoteAddr());
        if (outcome.failed()) {
            throw IdentityException.authFailed(); // thrown after the failure counter committed
        }
        if (outcome.result().mfaEnrolmentToken() != null) {
            return noStore().body(Map.of("status", "MFA_ENROLMENT_REQUIRED",
                    "enrolmentToken", outcome.result().mfaEnrolmentToken()));
        }
        return session(outcome.result().session());
    }

    @Operation(summary = "Rotate the refresh token (app)", description = "Send the current refresh token; get a new pair. "
            + "The old one stops working. Replaying a rotated token after 5 s revokes the whole session family.")
    @PostMapping("/refresh")
    public ResponseEntity<AppSession> refresh(@Valid @RequestBody AppRefreshRequest r, HttpServletRequest req) {
        return session(auth.refresh(r.refreshToken(), req.getHeader(HttpHeaders.USER_AGENT), req.getRemoteAddr()));
    }

    @Operation(summary = "Log out (app)", description = "Revokes the session family of this refresh token. Always 204.")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody AppRefreshRequest r, HttpServletRequest req) {
        auth.logout(r.refreshToken(), req.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<AppSession> session(IssuedTokens t) {
        return noStore().body(new AppSession(t.accessToken(), "Bearer", t.expiresInSeconds(), t.refreshToken(),
                t.refreshTtl().toSeconds()));
    }

    private static ResponseEntity.BodyBuilder noStore() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore());   // tokens must never sit in a cache
    }
}
