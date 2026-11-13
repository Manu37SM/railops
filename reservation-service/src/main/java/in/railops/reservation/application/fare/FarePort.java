package in.railops.reservation.application.fare;

import in.railops.reservation.domain.model.Quota;
import in.railops.reservation.domain.model.TravelClass;

public interface FarePort {

    FareQuote quote(String trainNo, TravelClass travelClass, Quota quota, int distanceKm);
}
