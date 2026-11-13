package in.railops.identity.web;

import in.railops.identity.service.AuthService;
import in.railops.identity.service.AuthService.LoginOutcome;
import in.railops.identity.service.IdentityException;
import in.railops.identity.service.MfaService;
import in.railops.identity.service.TokenService.IssuedTokens;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Browser session model: the access token lives in SPA memory only (never localStorage, where any XSS
 * can read it); the refresh token is an HttpOnly, Secure, SameSite=Strict cookie scoped to /api/v1/auth.
 * Because that cookie is sent automatically, refresh/logout also require a custom header: a cross-site
 * form can't set one, and a cross-site fetch that sets one needs a CORS preflight our allowlist refuses.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    static final String COOKIE = "rt";
    static final String CSRF_HEADER = "X-Requested-With";
    static final String CSRF_VALUE = "railops-web";

    public record RegisterRequest(@Email @NotBlank @Size(max = 254) String email,
                                  @NotBlank @Size(max = 128) String password,
                                  @NotBlank @Pattern(regexp = "^\\+?[0-9 -]{10,16}$") String phone) {
    }

    public record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 128) String password,
                               @Pattern(regexp = "\\d{6}") String otp) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
    }

    public record CodeRequest(@NotBlank @Pattern(regexp = "\\d{6}") String code) {
    }

    private final AuthService auth;
    private final MfaService mfa;
    private final boolean secureCookie;

    public AuthController(AuthService auth, MfaService mfa,
                          @Value("${railops.web.secure-cookie:true}") boolean secureCookie) {
        this.auth = auth;
        this.mfa = mfa;
        this.secureCookie = secureCookie;
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Register a passenger account", description = "409 ACCOUNT_EXISTS if the email or phone is taken; 422 WEAK_PASSWORD.")
    @PostMapping("/register")
    public ResponseEntity<Map<String, UUID>> register(@Valid @RequestBody RegisterRequest r, HttpServletRequest req) {
        UUID id = auth.registerPassenger(r.email(), r.password(), r.phone(), req.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("userId", id));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Log in", description = "Returns an access token and sets the HttpOnly refresh cookie, or MFA_ENROLMENT_REQUIRED with a 5-minute enrolment token. Staff must send otp. All failures are an identical 401.")
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest r, HttpServletRequest req) {
        LoginOutcome outcome = auth.login(r.email(), r.password(), r.otp(), req.getHeader(HttpHeaders.USER_AGENT),
                req.getRemoteAddr());
        if (outcome.failed()) {
            throw IdentityException.authFailed(); // thrown after the failure counter committed
        }
        if (outcome.result().mfaEnrolmentToken() != null) {
            return ResponseEntity.ok(Map.of("status", "MFA_ENROLMENT_REQUIRED",
                    "enrolmentToken", outcome.result().mfaEnrolmentToken()));
        }
        return session(outcome.result().session());
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Rotate the refresh cookie and get a new access token", description = "Requires X-Requested-With: railops-web. Replaying a rotated token after 5 s revokes the whole session family.")
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@CookieValue(name = COOKIE, required = false) String rt,
                                                 @RequestHeader(name = CSRF_HEADER, required = false) String csrf,
                                                 HttpServletRequest req) {
        requireCsrfHeader(csrf);
        return session(auth.refresh(rt, req.getHeader(HttpHeaders.USER_AGENT), req.getRemoteAddr()));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Revoke the session family and clear the cookie", description = "Requires X-Requested-With: railops-web.")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = COOKIE, required = false) String rt,
                                       @RequestHeader(name = CSRF_HEADER, required = false) String csrf,
                                       HttpServletRequest req) {
        requireCsrfHeader(csrf);
        auth.logout(rt, req.getRemoteAddr());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Start TOTP enrolment", description = "Enrolment token only. Returns the secret and otpauth URI once.")
    @PostMapping("/mfa/enrol")
    public MfaService.Enrolment beginEnrolment(@AuthenticationPrincipal Jwt jwt) {
        return mfa.begin(UUID.fromString(jwt.getSubject()));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Confirm TOTP enrolment with a valid code", description = "Activates the account.")
    @PostMapping("/mfa/confirm")
    public ResponseEntity<Void> confirmEnrolment(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CodeRequest r) {
        mfa.confirm(UUID.fromString(jwt.getSubject()), r.code());
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<TokenResponse> session(IssuedTokens t) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(t.refreshToken(), t.refreshTtl()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new TokenResponse(t.accessToken(), "Bearer", t.expiresInSeconds()));
    }

    private ResponseCookie cookie(String value, Duration ttl) {
        return ResponseCookie.from(COOKIE, value).httpOnly(true).secure(secureCookie).sameSite("Strict")
                .path("/api/v1/auth").maxAge(ttl).build();
    }

    private static void requireCsrfHeader(String value) {
        if (!CSRF_VALUE.equals(value)) {
            throw new IdentityException("CSRF_HEADER_MISSING", HttpStatus.FORBIDDEN, "missing request header");
        }
    }
}
