package in.railops.reservation.domain.policy;

import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.error.ErrorCode;
import in.railops.reservation.domain.model.PassengerSpec;
import in.railops.reservation.domain.model.PassengerStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides CNF / RAC / WL per passenger from free counts. Pure function: no I/O, so the
 * whole decision table is unit-testable. Berth *choice* happens later in the engine.
 */
public final class AllocationPlanner {

    public record Planned(PassengerSpec spec, PassengerStatus status) {
    }

    public record Plan(List<Planned> passengers, int cnfCount, int racSlots, int wlCount) {

        public boolean hasShortage() {
            return racSlots > 0 || wlCount > 0;
        }
    }

    public Plan plan(List<PassengerSpec> passengers, int cnfFree, int racSlotsFree, boolean onlyIfConfirmed) {
        if (cnfFree < 0 || racSlotsFree < 0) {
            throw new IllegalArgumentException("negative free count");
        }
        List<PassengerSpec> ordered = passengers.stream().sorted(Comparator.comparingInt(PassengerSpec::seqNo)).toList();
        List<Planned> out = new ArrayList<>(ordered.size());
        int cnf = 0;
        int rac = 0;
        int wl = 0;
        for (PassengerSpec p : ordered) {
            if (!p.berthRequired()) {
                out.add(new Planned(p, PassengerStatus.NOSB));
            } else if (cnf < cnfFree) {
                cnf++;
                out.add(new Planned(p, PassengerStatus.CNF));
            } else if (rac < racSlotsFree) {
                rac++;
                out.add(new Planned(p, PassengerStatus.RAC));
            } else {
                wl++;
                out.add(new Planned(p, PassengerStatus.WL));
            }
        }
        if (onlyIfConfirmed && (rac > 0 || wl > 0)) {
            throw new DomainException(ErrorCode.RES_ONLY_IF_CNF_UNMET,
                    "only " + cnfFree + " confirmed berths available for " + (cnf + rac + wl) + " passengers");
        }
        if (cnf + rac + wl == 0) {
            throw new DomainException(ErrorCode.RES_INVALID_JOURNEY, "at least one passenger must require a berth");
        }
        return new Plan(List.copyOf(out), cnf, rac, wl);
    }
}
