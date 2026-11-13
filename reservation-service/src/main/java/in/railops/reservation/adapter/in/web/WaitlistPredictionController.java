package in.railops.reservation.adapter.in.web;

import in.railops.reservation.adapter.out.persistence.PredictionRepository;
import in.railops.reservation.application.TransactionRunner;
import in.railops.reservation.application.prediction.WaitlistPredictionService;
import in.railops.reservation.application.prediction.WaitlistPredictionService.Prediction;
import in.railops.reservation.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

/** Present only when railops.prediction.wl.enabled=true; otherwise these paths 404 (denyAll for the staff one). */
@RestController
@Validated
// Not @ConditionalOnBean: for component-scanned classes it is evaluated before PredictionConfig's beans exist, so the
// controller silently never registered (caught by OpenApiDriftIT). Same property as PredictionConfig instead.
@ConditionalOnProperty(name = "railops.prediction.wl.enabled", havingValue = "true")
@Tag(name = "Predictions", description = "Advisory waitlist-confirmation estimates. Never used for allocation.")
public class WaitlistPredictionController {

    private final WaitlistPredictionService predictions;
    private final CurrentUserProvider currentUser;
    private final PredictionRepository repo;
    private final TransactionRunner tx;

    public WaitlistPredictionController(WaitlistPredictionService predictions, CurrentUserProvider currentUser,
                                        PredictionRepository repo, TransactionRunner tx) {
        this.predictions = predictions;
        this.currentUser = currentUser;
        this.repo = repo;
        this.tx = tx;
    }

    @GetMapping("/api/v1/bookings/{pnr}/wl-prediction")
    @Operation(summary = "Chance that my waitlisted booking confirms by chart time",
            description = "probability is null with a reason when there is no estimate (not waitlisted, chart "
                    + "prepared, class/quota not covered by the model). 404 for unknown and foreign PNRs alike.")
    public Prediction forBooking(@PathVariable @Pattern(regexp = BookingController.PNR) String pnr) {
        return predictions.forBooking(currentUser.requireUserId(), pnr);
    }

    @GetMapping("/api/v1/runs/{runId}/wl-prediction")
    @Operation(summary = "Chance that the next waitlist number for this pool confirms",
            description = "For a single passenger booking now. Public and cacheable for 60 s.")
    public ResponseEntity<Prediction> forPool(@PathVariable @Min(1) long runId,
                                              @RequestParam("class") @Pattern(regexp = "1A|2A|3A|3E|SL|CC|EC|2S") String cls,
                                              @RequestParam(name = "quota", defaultValue = "GN")
                                              @Pattern(regexp = "GN|LD|SS|TQ|PT|DF|FT|HP|PQ|RL") String quota) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(predictions.forNextWaitlist(runId, cls, quota));
    }

    public record ModelHealth(String modelVersion, String trainedOn, long resolved30d, Double brier30d,
                              Double meanPredicted30d, Double observedRate30d) {
    }

    @GetMapping("/api/v1/staff/ml/wl-model")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "WL model version and realised calibration over the last 30 days", description = "ADMIN, MFA.")
    public ModelHealth health() {
        var m = predictions.model();
        var c = tx.inNewTransaction(() -> repo.calibration(m.version(), Instant.now().minus(Duration.ofDays(30))));
        boolean any = c.n() > 0;
        return new ModelHealth(m.version(), m.trainedOn(), c.n(), any ? c.brier() : null,
                any ? c.meanPredicted() : null, any ? c.observedRate() : null);
    }
}
