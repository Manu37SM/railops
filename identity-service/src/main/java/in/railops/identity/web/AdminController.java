package in.railops.identity.web;

import in.railops.common.security.ScopeGrant;
import in.railops.identity.service.AuditService;
import in.railops.identity.service.UserAdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    public record CreateStaffRequest(@Email @NotBlank String email) {
    }

    public record GrantRequest(@NotBlank String role, @NotNull ScopeGrant.ScopeType scopeType, String scopeValue) {
    }

    private final UserAdminService admin;
    private final AuditService audit;
    private final in.railops.identity.raillens.AccountMoveService accountMove;

    public AdminController(UserAdminService admin, AuditService audit,
                           in.railops.identity.raillens.AccountMoveService accountMove) {
        this.admin = admin;
        this.audit = audit;
        this.accountMove = accountMove;
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Move RailLens accounts into this login",
            description = "ADMIN + MFA (single login, Phase 4C). Reads RailLens' accounts that aren't linked yet, using "
                    + "your own token, creates each here with the same email and password, and sends RailLens the new ids. "
                    + "Safe to repeat. An email that already has an account here is reported, never merged; RailLens "
                    + "admins are listed so you can grant roles yourself. Every account created is audited.")
    @PostMapping("/raillens/account-move")
    public ResponseEntity<in.railops.identity.raillens.AccountMoveService.Summary> moveRailLensAccounts(
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest req) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(accountMove.move(UUID.fromString(jwt.getSubject()), jwt.getTokenValue(), req.getRemoteAddr()));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Create a staff account (PENDING_MFA) with a one-time password", description = "ADMIN + MFA. The password is returned once; hand it over out of band.")
    @PostMapping("/staff")
    public ResponseEntity<UserAdminService.CreatedStaff> createStaff(@AuthenticationPrincipal Jwt jwt,
                                                                     @Valid @RequestBody CreateStaffRequest r,
                                                                     HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Cache-Control", "no-store") // response carries a one-time password
                .body(admin.createStaff(UUID.fromString(jwt.getSubject()), r.email(), req.getRemoteAddr()));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Grant a scoped role", description = "ADMIN + MFA. No self-grants; ADMIN/AUDITOR/REVENUE_ANALYST are GLOBAL-only, others must be scoped.")
    @PostMapping("/users/{userId}/grants")
    public ResponseEntity<Void> grant(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
                                      @Valid @RequestBody GrantRequest r, HttpServletRequest req) {
        admin.grant(UUID.fromString(jwt.getSubject()), userId, r.role(), r.scopeType(), r.scopeValue(), req.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Revoke a grant and the user's refresh tokens", description = "Existing access tokens expire within 10 minutes.")
    @DeleteMapping("/grants/{grantId}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable long grantId,
                                       HttpServletRequest req) {
        admin.revoke(UUID.fromString(jwt.getSubject()), grantId, req.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Verify the audit hash chain", description = "ADMIN or AUDITOR + MFA. Returns the first broken entry id if tampering is detected.")
    @GetMapping("/audit/verify")
    public AuditService.VerifyResult verifyAudit() {
        return audit.verify();
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Verify the audit log against published anchors",
            description = "ADMIN or AUDITOR + MFA. Body: anchors exported from WORM storage. Detects rewrites that "
                    + "recompute the whole chain, and deleted anchored rows, which /audit/verify alone cannot.")
    @org.springframework.web.bind.annotation.PostMapping("/audit/verify-anchors")
    public AuditService.AnchorVerifyResult verifyAnchors(
            @org.springframework.web.bind.annotation.RequestBody @jakarta.validation.constraints.Size(max = 100_000)
            List<AuditService.Anchor> anchors) {
        for (AuditService.Anchor a : anchors) {
            if (a.hash() == null || !a.hash().matches("[0-9a-f]{64}")) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "anchor hash must be 64 lowercase hex chars");
            }
        }
        return audit.verifyAgainst(anchors);
    }
}
