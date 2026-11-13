package in.railops.reservation.application.fare;

import java.math.BigDecimal;
import java.util.Map;

/** Per-passenger fare for one berth on one journey, with its components. All amounts in INR, scale 2. */
public record FareQuote(BigDecimal perPassenger, Map<String, BigDecimal> components) {
}
