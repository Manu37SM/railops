package in.railops.reservation.domain.model;

/**
 * Occupancy of a berth. FULL overlaps both RAC halves; the two RAC halves don't overlap each other,
 * so one exclusion constraint covers confirmed berths and shared RAC berths.
 */
public enum SlotRange {
    FULL("[1,3)"),
    RAC_A("[1,2)"),
    RAC_B("[2,3)");

    public final String pg;

    SlotRange(String pg) {
        this.pg = pg;
    }

    public boolean isRac() {
        return this != FULL;
    }

    public static SlotRange fromPg(String value) {
        for (SlotRange s : values()) {
            if (s.pg.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown slot range " + value);
    }
}
