package in.railops.reservation.domain.model;

public enum Quota {
    GN, LD, SS, TQ, PT, DF, FT, HP, PQ, RL;

    public boolean isTatkal() {
        return this == TQ || this == PT;
    }
}
