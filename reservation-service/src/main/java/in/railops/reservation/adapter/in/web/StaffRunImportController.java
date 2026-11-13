package in.railops.reservation.adapter.in.web;

import in.railops.reservation.application.schedule.RunImportService;
import in.railops.reservation.application.schedule.RunImportService.ImportCommand;
import in.railops.reservation.application.schedule.RunImportService.ImportResult;
import in.railops.reservation.domain.model.TravelClass;
import in.railops.reservation.domain.policy.RunPlanner.CoachSpec;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Opens dated runs of a train for booking, from its RailLens timetable (Phase 3B). */
@RestController
@Tag(name = "Staff", description = "Scoped staff operations (MFA required)")
@SecurityRequirement(name = "bearer")
@RequestMapping("/api/v1/staff/runs")
public class StaffRunImportController {

    public record CoachRequest(@NotNull TravelClass travelClass, @Min(1) @Max(24) int count) {
    }

    public record ImportRunsRequest(
            @NotNull @Pattern(regexp = "\\d{5}", message = "must be a 5-digit train number") String trainNo,
            @NotNull LocalDate fromDate,
            @NotNull LocalDate toDate,
            @Size(max = 8) List<@Valid CoachRequest> coaches,
            /** Phase 6: a standard coach set (RAJDHANI, MAIL_EXPRESS, SHATABDI...) instead of listing coaches. */
            @Pattern(regexp = "[A-Z_]{3,20}") String consist,
            @Min(0) @Max(50) Integer tatkalPercent,
            @Min(0) @Max(200) Integer waitlistPercent) {
    }

    private final RunImportService imports;

    /** Exactly one of: a standard coach set, or a list of coaches. */
    static java.util.List<CoachSpec> consist(ImportRunsRequest req) {
        boolean hasList = req.coaches() != null && !req.coaches().isEmpty();
        boolean hasSet = req.consist() != null && !req.consist().isBlank();
        if (hasList == hasSet) {
            throw new in.railops.reservation.domain.error.DomainException(
                    in.railops.reservation.domain.error.ErrorCode.RES_TIMETABLE_INVALID,
                    "give either a standard coach set or a list of coaches");
        }
        if (hasSet) {
            var set = in.railops.reservation.domain.policy.RunPlanner.STANDARD_CONSISTS.get(req.consist());
            if (set == null) {
                throw new in.railops.reservation.domain.error.DomainException(
                        in.railops.reservation.domain.error.ErrorCode.RES_TIMETABLE_INVALID,
                        "unknown coach set " + req.consist() + "; known: "
                                + new java.util.TreeSet<>(in.railops.reservation.domain.policy.RunPlanner.STANDARD_CONSISTS.keySet()));
            }
            return set;
        }
        return req.coaches().stream().map(c -> new CoachSpec(c.travelClass(), c.count())).toList();
    }

    public StaffRunImportController(RunImportService imports) {
        this.imports = imports;
    }

    @Operation(summary = "Create train runs from the RailLens timetable",
            description = "ADMIN with a GLOBAL grant; MFA required. One run per date in [fromDate, toDate] (max 62). "
                    + "Dates that already have a run are left unchanged (ALREADY_EXISTS); past or too-far dates are "
                    + "SKIPPED. Coaches: any class (1A, 2A, 3A, 3E, SL, CC, EC, 2S), or a standard coach set in `consist`. Seats come from the coach layouts; Tatkal takes "
                    + "tatkalPercent (default 10) of confirmed seats. Each created run is audited.")
    @PostMapping("/import")
    @PreAuthorize("@runAccess.canPlanRuns(authentication)")
    public ImportResult importRuns(@Valid @RequestBody ImportRunsRequest req, Authentication auth) {
        return imports.importRuns(new ImportCommand(req.trainNo(), req.fromDate(), req.toDate(), consist(req),
                req.tatkalPercent(), req.waitlistPercent()), auth.getName());
    }
}
