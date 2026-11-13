package in.railops.reservation.adapter.in.web;

import in.railops.reservation.domain.model.Quota;
import in.railops.reservation.domain.model.TravelClass;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Wire DTO. Deliberately has no fare/amount field: the server is the only source of price. */
public record CreateBookingRequest(
        @Positive long trainRunId,
        @Min(0) int fromStopSeq,
        @Min(1) int toStopSeq,
        @NotNull TravelClass travelClass,
        @NotNull Quota quota,
        @NotNull @Size(min = 1, max = 6) List<@Valid PassengerRequest> passengers,
        boolean onlyIfConfirmed,
        @NotBlank @Pattern(regexp = "^\\+?[0-9 -]{10,16}$") String contactPhone,
        @Email @Size(max = 254) String contactEmail) {

    public record PassengerRequest(
            @NotBlank @Size(max = 60) @Pattern(regexp = "^[\\p{L} .'-]+$") String name,
            @Min(0) @Max(125) int age,
            @NotNull @Pattern(regexp = "[MFT]") String gender,
            @Pattern(regexp = "LB|MB|UB|SL|SU|WS|MS|AS") String berthPreference,
            Boolean berthRequired) {
    }
}
