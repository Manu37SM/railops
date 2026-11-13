package in.railops.reservation.domain.model;

/** Half-open segment interval [from, to): boarding at stop {@code from}, alighting at stop {@code to}. */
public record SegmentRange(int from, int to) {

    public SegmentRange {
        if (from < 0 || to <= from) {
            throw new IllegalArgumentException("invalid segment range " + from + ".." + to);
        }
    }

    public boolean overlaps(SegmentRange other) {
        return from < other.to && other.from < to;
    }

    public boolean contains(int segIdx) {
        return segIdx >= from && segIdx < to;
    }

    public int length() {
        return to - from;
    }

    public String toPg() {
        return "[" + from + "," + to + ")";
    }
}
