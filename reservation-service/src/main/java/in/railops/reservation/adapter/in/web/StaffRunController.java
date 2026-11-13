package in.railops.reservation.adapter.in.web;

import in.railops.reservation.adapter.out.persistence.PredictionRepository;
import in.railops.reservation.adapter.out.persistence.RunReportRepository;
import in.railops.reservation.application.ChartService;
import in.railops.reservation.application.ChartService.ChartResult;
import in.railops.reservation.application.RunCancellationService;
import in.railops.reservation.application.StaffAudit;
import in.railops.reservation.application.StaffReports.PoolOccupancy;
import in.railops.reservation.application.StaffReports.RunAnalytics;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Staff operations on one run. Two layers: the filter chain demands an MFA-authenticated token, and
 * {@code @PreAuthorize} checks the caller's scope covers a station on this run. Every access is audited (IAM-3);
 * reads are audited in the same transaction as the read.
 *
 * <p>Phase 15: SQL moved to {@link RunReportRepository}, audit to {@link StaffAudit}; behaviour unchanged.
 */
@RestController
@Tag(name = "Staff", description = "Scoped staff operations (MFA required)")
@SecurityRequirement(name = "bearer")
@RequestMapping("/api/v1/staff/runs")
public class StaffRunController {

    public record ChartRequest(@Min(1) @Max(2) int chartNo) {
    }

    public record CancelRunRequest(@NotBlank @Size(max = 200) String reason) {
    }

    private final ChartService charts;
    private final RunCancellationService runCancellations;
    private final RunReportRepository reports;
    private final PredictionRepository predictions;
    private final StaffAudit audit;

    public StaffRunController(ChartService charts, RunCancellationService runCancellations, RunReportRepository reports,
                              PredictionRepository predictions, StaffAudit audit) {
        this.charts = charts;
        this.runCancellations = runCancellations;
        this.reports = reports;
        this.predictions = predictions;
        this.audit = audit;
    }

    /**
     * Manual chart trigger (normally the scheduler does it at chart1_due_at / chart2_due_at). Only controllers
     * scoped to a station on this run, or admins. Idempotent: a prepared chart returns ALREADY_PREPARED.
     */
    @Operation(summary = "Prepare chart 1 or 2 for a run", description = "CONTROLLER scoped to a station/division/zone on the run, or ADMIN; MFA required. Idempotent; audited.")
    @PostMapping("/{runId}/charts")
    @PreAuthorize("@runAccess.canOperate(authentication, #runId)")
    public ChartResult prepareChart(@PathVariable long runId, @Valid @RequestBody ChartRequest req, Authentication auth) {
        var result = charts.prepare(runId, req.chartNo(), auth.getName());
        audit.action(auth.getName(), "PREPARE_CHART_" + req.chartNo(), runId);
        return result;
    }

    @Operation(summary = "Cancel a train run",
            description = "CONTROLLER in scope or ADMIN; MFA. Blocks new bookings immediately; every passenger is then "
                    + "refunded in full asynchronously (RunCancelled on ops.disruption.v1 -> bulk job). Idempotent.")
    @PostMapping("/{runId}/cancel")
    @PreAuthorize("@runAccess.canOperate(authentication, #runId)")
    public ResponseEntity<Map<String, Object>> cancelRun(@PathVariable long runId, @Valid @RequestBody CancelRunRequest req,
                                                         Authentication auth) {
        runCancellations.cancelRun(runId, req.reason(), auth.getName());
        return ResponseEntity.accepted().body(Map.of("trainRunId", runId, "status", "CANCELLED", "refunds", "IN_PROGRESS"));
    }

    @Operation(summary = "Occupancy per class and quota", description = "Any staff role whose scope covers a station on the run; MFA required. Every access is audited.")
    @GetMapping("/{runId}/occupancy")
    @PreAuthorize("@runAccess.canView(authentication, #runId)")
    @Transactional
    public List<PoolOccupancy> occupancy(@PathVariable long runId, Authentication auth) {
        List<PoolOccupancy> result = reports.occupancy(runId);
        audit.access(auth.getName(), "VIEW_RUN_OCCUPANCY", runId);
        return result;
    }

    @Operation(summary = "Run analytics: occupancy, WL funnel, cancellations, indicative revenue",
            description = "Same access rule as occupancy (scoped staff, MFA). Money is indicative; the payment ledger is authoritative. Audited.")
    @GetMapping("/{runId}/analytics")
    @PreAuthorize("@runAccess.canView(authentication, #runId)")
    @Transactional
    public RunAnalytics analytics(@PathVariable long runId, Authentication auth) {
        var pools = reports.funnel(runId);
        var money = reports.money(runId);
        var pred = predictions.runStats(runId);
        audit.access(auth.getName(), "VIEW_RUN_ANALYTICS", runId);
        return new RunAnalytics(runId, pools, money.paidBookings(), money.userCancellations(), money.grossBookedFare(),
                money.refunds(), money.grossBookedFare().subtract(money.refunds()), pred.logged(), pred.resolved(),
                pred.brier() == null ? null : Math.round(pred.brier() * 10_000.0) / 10_000.0);
    }
}
