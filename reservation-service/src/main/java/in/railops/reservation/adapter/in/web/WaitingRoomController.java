package in.railops.reservation.adapter.in.web;

import in.railops.reservation.application.WaitingRoomService;
import in.railops.reservation.application.WaitingRoomService.Status;
import in.railops.reservation.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@Tag(name = "Waiting room", description = "Fair admission for Tatkal openings")
@SecurityRequirement(name = "bearer")
public class WaitingRoomController {

    public record OpenRequest(@NotNull Instant opensAt, @Min(1) @Max(10000) int admitPerSecond) {
    }

    private final WaitingRoomService rooms;
    private final CurrentUserProvider currentUser;

    public WaitingRoomController(WaitingRoomService rooms, CurrentUserProvider currentUser) {
        this.rooms = rooms;
        this.currentUser = currentUser;
    }

    @PostMapping("/api/v1/runs/{runId}/waiting-room")
    @Operation(summary = "Join the waiting room", description = "Joining before opening time gives a random place among early "
            + "joiners (arriving first is no advantage); after opening, places are first come first served. Re-joining keeps your place.")
    public Status join(@PathVariable long runId) {
        return rooms.join(runId, currentUser.requireUserId());
    }

    @GetMapping("/api/v1/runs/{runId}/waiting-room")
    @Operation(summary = "My waiting-room status", description = "Poll every few seconds. ADMITTED lasts 10 minutes.")
    public Status status(@PathVariable long runId) {
        return rooms.status(runId, currentUser.requireUserId());
    }

    @PutMapping("/api/v1/staff/runs/{runId}/waiting-room")
    @PreAuthorize("@runAccess.canOperate(authentication, #runId)")
    @Operation(summary = "Open a waiting room for a run", description = "CONTROLLER in scope or ADMIN. Bookings for the run then "
            + "require admission.")
    public ResponseEntity<Void> open(@PathVariable long runId, @Valid @RequestBody OpenRequest req) {
        rooms.open(runId, req.opensAt(), req.admitPerSecond());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/v1/staff/runs/{runId}/waiting-room")
    @PreAuthorize("@runAccess.canOperate(authentication, #runId)")
    @Operation(summary = "Close the waiting room (kill switch)")
    public ResponseEntity<Void> close(@PathVariable long runId) {
        rooms.close(runId);
        return ResponseEntity.noContent().build();
    }
}
