package in.railops.reservation.application;

import in.railops.reservation.domain.model.PassengerSpec;
import in.railops.reservation.domain.model.Quota;
import in.railops.reservation.domain.model.TravelClass;

import java.util.List;

public record BookingCommand(long trainRunId, int fromSeq, int toSeq, TravelClass travelClass, Quota quota,
                             List<PassengerSpec> passengers, boolean onlyIfConfirmed,
                             String contactPhone, String contactEmail) {

    public BookingCommand {
        passengers = List.copyOf(passengers);
        long distinctSeq = passengers.stream().mapToInt(PassengerSpec::seqNo).distinct().count();
        if (passengers.isEmpty() || passengers.size() > 6 || distinctSeq != passengers.size()) {
            throw new IllegalArgumentException("1-6 passengers with distinct sequence numbers required");
        }
    }
}
