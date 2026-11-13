package in.railops.reservation.adapter.in.web;

import in.railops.reservation.application.AvailabilityService;
import in.railops.reservation.application.AvailabilityService.RunAvailability;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@Validated
@Tag(name = "Availability", description = "Public seat availability (no login required)")
public class AvailabilityController {

    private final AvailabilityService availability;

    public AvailabilityController(AvailabilityService availability) {
        this.availability = availability;
    }

    @GetMapping("/api/v1/runs/{runId}/availability")
    @Operation(summary = "Availability per class and quota for a journey",
            description = "Advisory and briefly cacheable: the booking call re-checks under locks. "
                    + "Display values: AVAILABLE-n, RAC-n, WL-n (next WL number), REGRET, BOOKING_CLOSED.")
    public ResponseEntity<RunAvailability> availability(@PathVariable @Min(1) long runId,
                                                        @RequestParam("from") @Min(0) int fromSeq,
                                                        @RequestParam("to") @Min(1) int toSeq,
                                                        @RequestParam(name = "class", required = false)
                                                        @Pattern(regexp = "1A|2A|3A|3E|SL|CC|EC|2S") String classCode) {
        // 5 s at the edge absorbs refresh-storms during Tatkal without making the number meaningfully stale.
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(5)).cachePublic())
                .body(availability.forJourney(runId, fromSeq, toSeq, classCode));
    }
}
