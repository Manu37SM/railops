package in.railops.reservation.application.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Serving side of {@code analytics/railops_ml}: logistic regression over standardised features, then a piecewise-linear
 * calibration map. Immutable and thread-safe. Every transform here must match {@code features.py} exactly: the golden
 * parity test scores the cases Python exported and fails on any difference above 1e-9.
 *
 * <p>Loading fails fast (exception at startup) on any shape mismatch: a half-loaded model that silently scores with
 * the wrong coefficient order is worse than no prediction.
 */
public final class WaitlistModel {

    /** Feature order understood by this code. A model JSON listing anything else is rejected. */
    static final List<String> SUPPORTED_FEATURES = List.of("wl_ratio", "wl_hinge_0.1", "wl_hinge_0.25", "wl_hinge_0.5",
            "log_days", "party", "velocity", "cancel_ratio", "weekend_peak", "midweek", "festival", "summer", "cls_SL",
            "train_prior");
    static final int SUPPORTED_FEATURE_VERSION = 1;
    /** What the training data covered. Anything else gets no prediction rather than an extrapolation. */
    static final Set<String> CLASSES = Set.of("3A", "SL");
    static final Set<String> QUOTAS = Set.of("GN");

    public record Band(String name, double below) {
    }

    private final String modelVersion;
    private final String trainedOn;
    private final double[] mean;
    private final double[] std;
    private final double[] coef;
    private final double intercept;
    private final double[] isoX;
    private final double[] isoY;
    private final double globalPrior;
    private final Map<String, Double> trainPrior;
    private final List<int[]> festivalWindows;
    private final Set<Integer> summerMonths;
    private final Map<String, double[]> clip;
    private final double[] knots = {0.1, 0.25, 0.5};
    private final List<Band> bands;

    private WaitlistModel(JsonNode doc) {
        if (doc.path("featureVersion").asInt() != SUPPORTED_FEATURE_VERSION) {
            throw new IllegalStateException("unsupported featureVersion " + doc.path("featureVersion"));
        }
        List<String> names = new ArrayList<>();
        doc.path("features").forEach(n -> names.add(n.asText()));
        if (!names.equals(SUPPORTED_FEATURES)) {
            throw new IllegalStateException("model features " + names + " != supported " + SUPPORTED_FEATURES);
        }
        this.modelVersion = require(doc, "modelVersion").asText();
        this.trainedOn = doc.path("trainedOn").asText("");
        this.mean = doubles(require(doc, "mean"), names.size());
        this.std = doubles(require(doc, "std"), names.size());
        this.coef = doubles(require(doc, "coef"), names.size());
        this.intercept = require(doc, "intercept").asDouble();
        JsonNode iso = require(doc, "isotonic");
        this.isoX = doubles(iso.path("x"), -1);
        this.isoY = doubles(iso.path("y"), isoX.length);
        for (int i = 1; i < isoX.length; i++) {
            if (isoX[i] < isoX[i - 1] || isoY[i] < isoY[i - 1]) {
                throw new IllegalStateException("calibration map is not monotone at index " + i);
            }
        }
        if (isoX.length < 2) {
            throw new IllegalStateException("calibration map needs at least two points");
        }
        for (double s : std) {
            if (!(s > 0)) {
                throw new IllegalStateException("non-positive std in model");
            }
        }
        JsonNode prior = require(doc, "trainPrior");
        this.globalPrior = prior.path("global").asDouble();
        Map<String, Double> byTrain = new HashMap<>();
        prior.path("byTrain").fields().forEachRemaining(e -> byTrain.put(e.getKey(), e.getValue().asDouble()));
        this.trainPrior = Map.copyOf(byTrain);
        List<int[]> windows = new ArrayList<>();
        doc.path("festivalWindows").forEach(w -> windows.add(new int[]{w.get(0).asInt(), w.get(1).asInt(),
                w.get(2).asInt(), w.get(3).asInt()}));
        this.festivalWindows = List.copyOf(windows);
        Set<Integer> summer = new java.util.HashSet<>();
        doc.path("summerMonths").forEach(m -> summer.add(m.asInt()));
        this.summerMonths = Set.copyOf(summer);
        Map<String, double[]> c = new HashMap<>();
        require(doc, "clip").fields().forEachRemaining(e -> c.put(e.getKey(), doubles(e.getValue(), 2)));
        for (String k : List.of("wl_ratio", "days_before", "party", "velocity", "cancel_ratio")) {
            if (!c.containsKey(k)) {
                throw new IllegalStateException("clip bounds missing for " + k);
            }
        }
        this.clip = Map.copyOf(c);
        List<Band> b = new ArrayList<>();
        doc.path("bands").forEach(n -> b.add(new Band(n.path("name").asText(), n.path("below").asDouble())));
        if (b.isEmpty()) {
            throw new IllegalStateException("no probability bands");
        }
        this.bands = List.copyOf(b);
    }

    public static WaitlistModel load(InputStream in, ObjectMapper json) throws IOException {
        return new WaitlistModel(json.readTree(in));
    }

    /** Observable state of one waitlisted party (or a hypothetical party, for availability) at prediction time. */
    public record Input(String trainNo, LocalDate runDate, String classCode, String quotaCode, double daysBefore,
                        int party, int capacity, int wlPosition, int bookingsLast7, int cancelsSoFar) {
    }

    public boolean covers(Input in) {
        return CLASSES.contains(in.classCode()) && QUOTAS.contains(in.quotaCode()) && in.capacity() > 0
                && in.wlPosition() > 0;
    }

    public double[] features(Input in) {
        double cap = in.capacity();
        double wl = clip("wl_ratio", in.wlPosition() / cap);
        double[] x = new double[SUPPORTED_FEATURES.size()];
        int i = 0;
        x[i++] = wl;
        for (double k : knots) {
            x[i++] = Math.max(wl - k, 0.0);
        }
        x[i++] = Math.log1p(clip("days_before", in.daysBefore()));
        x[i++] = clip("party", in.party());
        x[i++] = clip("velocity", in.bookingsLast7() / cap);
        x[i++] = clip("cancel_ratio", in.cancelsSoFar() / cap);
        DayOfWeek wd = in.runDate().getDayOfWeek();
        x[i++] = (wd == DayOfWeek.FRIDAY || wd == DayOfWeek.SUNDAY) ? 1.0 : 0.0;
        x[i++] = (wd == DayOfWeek.TUESDAY || wd == DayOfWeek.WEDNESDAY) ? 1.0 : 0.0;
        x[i++] = festival(in.runDate()) ? 1.0 : 0.0;
        x[i++] = summerMonths.contains(in.runDate().getMonthValue()) ? 1.0 : 0.0;
        x[i++] = "SL".equals(in.classCode()) ? 1.0 : 0.0;
        x[i] = trainPrior.getOrDefault(in.trainNo(), globalPrior);
        return x;
    }

    /** Calibrated probability that the party holds berths (CNF or RAC) when the chart is prepared. */
    public double probability(double[] x) {
        double z = intercept;
        for (int i = 0; i < x.length; i++) {
            z += coef[i] * ((x[i] - mean[i]) / std[i]);
        }
        double raw = 1.0 / (1.0 + Math.exp(-z));
        return interpolate(raw);
    }

    public String band(double p) {
        for (Band b : bands) {
            if (p < b.below()) {
                return b.name();
            }
        }
        return bands.get(bands.size() - 1).name();
    }

    public String version() {
        return modelVersion;
    }

    public String trainedOn() {
        return trainedOn;
    }

    /** Same as sklearn IsotonicRegression(out_of_bounds="clip").predict: linear between thresholds, flat outside. */
    double interpolate(double v) {
        if (v <= isoX[0]) {
            return isoY[0];
        }
        int n = isoX.length;
        if (v >= isoX[n - 1]) {
            return isoY[n - 1];
        }
        int lo = 0;
        int hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (isoX[mid] <= v) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double span = isoX[hi] - isoX[lo];
        return span == 0 ? isoY[hi] : isoY[lo] + (v - isoX[lo]) * (isoY[hi] - isoY[lo]) / span;
    }

    boolean festival(LocalDate d) {
        int md = d.getMonthValue() * 100 + d.getDayOfMonth();
        for (int[] w : festivalWindows) {
            if (w[0] * 100 + w[1] <= md && md <= w[2] * 100 + w[3]) {
                return true;
            }
        }
        return false;
    }

    private double clip(String name, double v) {
        double[] b = clip.get(name);
        return Math.min(Math.max(v, b[0]), b[1]);
    }

    private static JsonNode require(JsonNode doc, String field) {
        JsonNode n = doc.get(field);
        if (n == null || n.isNull()) {
            throw new IllegalStateException("model JSON missing '" + field + "'");
        }
        return n;
    }

    private static double[] doubles(JsonNode arr, int expected) {
        if (!arr.isArray() || (expected >= 0 && arr.size() != expected)) {
            throw new IllegalStateException("expected array of " + expected + ", got " + arr);
        }
        double[] out = new double[arr.size()];
        for (int i = 0; i < out.length; i++) {
            if (!arr.get(i).isNumber()) {
                throw new IllegalStateException("non-numeric model value " + arr.get(i));
            }
            out[i] = arr.get(i).asDouble();
        }
        return out;
    }
}
