package in.railops.reservation.application.fare;

import in.railops.reservation.domain.model.Quota;
import in.railops.reservation.domain.model.TravelClass;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LOCAL PROFILE ONLY. Illustrative per-km rates so the service runs without catalog-service.
 * These are not real tariffs; production fares come from catalog-service (HttpFareClient).
 */
@Component
@Profile("sim")
public class LocalFareTable implements FarePort {

    private static final BigDecimal GST_AC = new BigDecimal("0.05");

    @Override
    public FareQuote quote(String trainNo, TravelClass c, Quota quota, int distanceKm) {
        BigDecimal perKm = switch (c) {
            case FIRST_AC -> new BigDecimal("3.20");
            case SECOND_AC -> new BigDecimal("1.90");
            case THIRD_AC -> new BigDecimal("1.30");
            case THIRD_AC_ECONOMY -> new BigDecimal("1.20");
            case SLEEPER -> new BigDecimal("0.50");
            case CHAIR_CAR -> new BigDecimal("1.10");
            case EXECUTIVE_CHAIR -> new BigDecimal("2.30");
            case SECOND_SITTING -> new BigDecimal("0.30");
        };
        Map<String, BigDecimal> parts = new LinkedHashMap<>();
        BigDecimal base = perKm.multiply(BigDecimal.valueOf(distanceKm)).setScale(2, RoundingMode.HALF_UP);
        parts.put("BASE", base);
        parts.put("RESERVATION", c == TravelClass.SLEEPER || c == TravelClass.SECOND_SITTING
                ? new BigDecimal("20.00") : new BigDecimal("40.00"));
        if (quota.isTatkal()) {
            parts.put("TATKAL", base.multiply(new BigDecimal("0.30")).setScale(2, RoundingMode.HALF_UP));
        }
        BigDecimal taxable = parts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean ac = c != TravelClass.SLEEPER && c != TravelClass.SECOND_SITTING;
        if (ac) {
            parts.put("GST", taxable.multiply(GST_AC).setScale(2, RoundingMode.HALF_UP));
        }
        BigDecimal total = parts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return new FareQuote(total, Map.copyOf(parts));
    }
}
