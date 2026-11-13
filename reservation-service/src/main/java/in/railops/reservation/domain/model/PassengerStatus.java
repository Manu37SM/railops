package in.railops.reservation.domain.model;

public enum PassengerStatus {
    CNF(3), RAC(2), WL(1), NOSB(3), CAN(0), NO_SHOW(0), CHART_CAN(0);

    /** Higher is better for the passenger. NOSB (child, no berth) never competes for inventory. */
    public final int rank;

    PassengerStatus(int rank) {
        this.rank = rank;
    }

    public boolean atLeastAsGoodAs(PassengerStatus other) {
        return rank >= other.rank;
    }
}
