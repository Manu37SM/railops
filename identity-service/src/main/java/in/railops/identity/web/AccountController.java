package in.railops.identity.web;

import in.railops.identity.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Phase 4D: the signed-in user's own account. Needs a user access token (security config demands AMR_pwd,
 * which enrolment and service tokens never carry) AND the current password, so a stolen access token alone
 * can neither change the password nor close the account. POST rather than DELETE for erase: some proxies
 * drop DELETE bodies.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AccountController {

    public record ChangePasswordRequest(@NotBlank @Size(max = 128) String currentPassword,
                                        @NotBlank @Size(max = 128) String newPassword) {
    }

    public record EraseRequest(@NotBlank @Size(max = 128) String password) {
    }

    private final AuthService auth;

    public AccountController(AuthService auth) {
        this.auth = auth;
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Change my password",
            description = "401 AUTH_FAILED if the current password is wrong (counts toward lockout); 422 WEAK_PASSWORD or PASSWORD_REUSED. Revokes every session, including this one: sign in again afterwards.")
    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest r,
                                               HttpServletRequest req) {
        auth.changePassword(UUID.fromString(jwt.getSubject()), r.currentPassword(), r.newPassword(), req.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Close my passenger account",
            description = "Erases email, phone, MFA secret, roles and sessions; bookings and payments are kept as financial records. 401 AUTH_FAILED on a wrong password; 403 ERASE_NOT_ALLOWED for staff.")
    @PostMapping("/erase")
    public ResponseEntity<Void> erase(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EraseRequest r, HttpServletRequest req) {
        auth.eraseSelf(UUID.fromString(jwt.getSubject()), r.password(), req.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }
}
