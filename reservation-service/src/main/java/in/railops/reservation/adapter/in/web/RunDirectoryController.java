package in.railops.reservation.adapter.in.web;

import in.railops.reservation.application.RunDirectoryService;
import in.railops.reservation.application.RunDirectoryService.RunSummary;
import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDate;

@RestController
@Validated
@Tag(name = "Availability", description = "Public seat availability (no login required)")
public class RunDirectoryController {

    private final RunDirectoryService directory;
    private final in.railops.reservation.application.SeatMapService seatMaps;

    public RunDirectoryController(RunDirectoryService directory, in.railops.reservation.application.SeatMapService seatMaps) {
        this.directory = directory;
        this.seatMaps = seatMaps;
    }

    @GetMapping("/api/v1/runs/{runId}/seat-map")
    @Operation(summary = "Live seat map of a class for a journey",
            description = "Phase 6. Public. Per coach: each berth or seat with its type and FREE / RAC_HALF / TAKEN over "
                    + "the journey's segments, from RailOps' own bookings (held or confirmed). Never says who booked. "
                    + "Cached 5 s, like availability.")
    public ResponseEntity<in.railops.reservation.application.SeatMapService.SeatMap> seatMap(
            @PathVariable @Min(1) long runId,
            @RequestParam("class") @Pattern(regexp = "1A|2A|3A|3E|SL|CC|EC|2S") String classCode,
            @RequestParam("from") @Min(0) int fromSeq,
            @RequestParam("to") @Min(1) int toSeq) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(5)).cachePublic())
                .body(seatMaps.map(runId, classCode, fromSeq, toSeq));
    }

    @GetMapping("/api/v1/runs")
    @Operation(summary = "Find the run of a train on a date",
            description = "Public. Stops (with the stop numbers availability and booking take) and the classes on sale. "
                    + "404 RES_RUN_NOT_FOUND when the train isn't open for booking on that date.")
    public ResponseEntity<RunSummary> find(@RequestParam @Pattern(regexp = "\\d{5}") String trainNo,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return cached(directory.find(trainNo, date).orElseThrow(RunDirectoryController::notFound));
    }

    @GetMapping("/api/v1/runs/{runId}")
    @Operation(summary = "One run by id", description = "Public. Same shape as the search above.")
    public ResponseEntity<RunSummary> byId(@PathVariable @Min(1) long runId) {
        return cached(directory.find(runId).orElseThrow(RunDirectoryController::notFound));
    }

    /** Stops and classes rarely change; a minute at the edge keeps the free tier calm. */
    private static ResponseEntity<RunSummary> cached(RunSummary s) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic()).body(s);
    }

    private static RuntimeException notFound() {
        return new DomainException(ErrorCode.RES_RUN_NOT_FOUND, "this train is not open for booking on that date");
    }
}
