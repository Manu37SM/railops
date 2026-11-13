package in.railops.reservation.domain.model;

/**
 * A passenger as requested. {@code berthRequired=false} is only valid for children under 5
 * (requirement RES-2a, found in Phase 4): they travel without consuming inventory.
 */
public record PassengerSpec(int seqNo, String name, int age, char gender, String berthPref, boolean berthRequired) {

    public static final int NO_BERTH_MAX_AGE = 4;

    public PassengerSpec {
        if (seqNo < 1 || seqNo > 6) throw new IllegalArgumentException("seqNo out of range");
        if (age < 0 || age > 125) throw new IllegalArgumentException("age out of range");
        if (gender != 'M' && gender != 'F' && gender != 'T') throw new IllegalArgumentException("gender");
        if (!berthRequired && age > NO_BERTH_MAX_AGE) {
            throw new IllegalArgumentException("only children under 5 may travel without a berth");
        }
    }

    public boolean lowerBerthPriority() {
        return age >= 60 || (gender == 'F' && age >= 45);
    }
}
