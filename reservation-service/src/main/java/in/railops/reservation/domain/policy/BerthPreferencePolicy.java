package in.railops.reservation.domain.policy;

import in.railops.reservation.domain.model.PassengerSpec;
import in.railops.reservation.domain.model.TravelClass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class BerthPreferencePolicy {

    /** Berth types to try first, best first. Empty means "no preference". */
    public List<String> preferredTypes(PassengerSpec p, TravelClass travelClass) {
        List<String> out = new ArrayList<>(3);
        if (travelClass.seated) {
            if (p.berthPref() != null && List.of("WS", "MS", "AS").contains(p.berthPref())) {
                out.add(p.berthPref());
            }
            return out;
        }
        if (p.lowerBerthPriority()) {
            out.add("LB");
        }
        if (p.berthPref() != null && !out.contains(p.berthPref())) {
            out.add(p.berthPref());
        }
        return out;
    }

    /**
     * Order in which passengers *choose berths*. Lower berths are the scarce resource, so the people
     * who need them most choose first. This doesn't affect who gets CNF vs RAC/WL; that follows seqNo.
     */
    public Comparator<PassengerSpec> pickOrder() {
        return Comparator.comparing((PassengerSpec p) -> !p.lowerBerthPriority())
                .thenComparingInt(PassengerSpec::seqNo);
    }
}
