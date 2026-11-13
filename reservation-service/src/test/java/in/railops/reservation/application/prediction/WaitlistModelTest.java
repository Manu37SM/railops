package in.railops.reservation.application.prediction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Training/serving parity and the model's safety properties. The golden file is written by
 * analytics/railops_ml/train.py from the SAME model JSON that ships in src/main/resources/ml.
 */
class WaitlistModelTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static WaitlistModel model() throws IOException {
        try (InputStream in = WaitlistModelTest.class.getResourceAsStream("/ml/wl-model.json")) {
            return WaitlistModel.load(in, JSON);
        }
    }

    @Test
    void java_scores_every_golden_case_exactly_as_python_did() throws IOException {
        WaitlistModel m = model();
        JsonNode golden = JSON.readTree(WaitlistModelTest.class.getResourceAsStream("/ml/golden_cases.json"));
        assertThat(golden.path("modelVersion").asText())
                .as("golden file and bundled model come from the same training run").isEqualTo(m.version());
        double tol = golden.path("tolerance").asDouble();
        int n = 0;
        for (JsonNode c : golden.path("cases")) {
            var in = new WaitlistModel.Input(c.path("train").asText(), LocalDate.parse(c.path("runDate").asText()),
                    c.path("cls").asText(), "GN", c.path("daysBefore").asDouble(), c.path("party").asInt(),
                    c.path("capacity").asInt(), c.path("wlPosition").asInt(), c.path("bookingsLast7").asInt(),
                    c.path("cancelsSoFar").asInt());
            double[] x = m.features(in);
            JsonNode expected = c.path("features");
            for (int i = 0; i < x.length; i++) {
                assertThat(x[i]).as("case %d feature %s", n, WaitlistModel.SUPPORTED_FEATURES.get(i))
                        .isCloseTo(expected.get(i).asDouble(), within(tol));
            }
            assertThat(m.probability(x)).as("case %d probability", n)
                    .isCloseTo(c.path("probability").asDouble(), within(tol));
            n++;
        }
        assertThat(n).isGreaterThanOrEqualTo(40);
    }

    @Test
    void a_worse_waitlist_position_never_scores_higher() throws IOException {
        WaitlistModel m = model();
        Random r = new Random(42);
        for (int t = 0; t < 2_000; t++) {
            int cap = 20 + r.nextInt(80);
            LocalDate d = LocalDate.of(2026, 1, 1).plusDays(r.nextInt(365));
            String cls = r.nextBoolean() ? "SL" : "3A";
            double days = r.nextDouble() * 60;
            int party = 1 + r.nextInt(6), recent = r.nextInt(150), cancels = r.nextInt(60);
            double prev = 2.0;
            for (int wl = 1; wl <= cap * 2; wl++) {   // beyond the clip bound too
                double p = m.probability(m.features(new WaitlistModel.Input("12001", d, cls, "GN", days, party, cap,
                        wl, recent, cancels)));
                assertThat(p).isBetween(0.0, 1.0).isLessThanOrEqualTo(prev + 1e-12);
                prev = p;
            }
        }
    }

    @Test
    void classes_and_quotas_outside_the_training_data_get_no_estimate() throws IOException {
        WaitlistModel m = model();
        var base = new WaitlistModel.Input("12001", LocalDate.of(2026, 7, 1), "3A", "GN", 20, 1, 64, 5, 10, 2);
        assertThat(m.covers(base)).isTrue();
        assertThat(m.covers(new WaitlistModel.Input("12001", base.runDate(), "2A", "GN", 20, 1, 64, 5, 10, 2))).isFalse();
        assertThat(m.covers(new WaitlistModel.Input("12001", base.runDate(), "3A", "TQ", 20, 1, 64, 5, 10, 2))).isFalse();
        assertThat(m.covers(new WaitlistModel.Input("12001", base.runDate(), "3A", "GN", 20, 1, 0, 5, 10, 2)))
                .as("zero capacity would divide by zero").isFalse();
    }

    @Test
    void unknown_trains_fall_back_to_the_global_prior() throws IOException {
        WaitlistModel m = model();
        var in = new WaitlistModel.Input("00000", LocalDate.of(2026, 7, 1), "3A", "GN", 20, 1, 64, 5, 10, 2);
        double[] x = m.features(in);
        double global = JSON.readTree(WaitlistModelTest.class.getResourceAsStream("/ml/wl-model.json"))
                .path("trainPrior").path("global").asDouble();
        assertThat(x[x.length - 1]).isEqualTo(global);
    }

    @Test
    void festival_windows_are_inclusive_and_bands_partition_the_unit_interval() throws IOException {
        WaitlistModel m = model();
        assertThat(m.festival(LocalDate.of(2026, 10, 15))).isTrue();
        assertThat(m.festival(LocalDate.of(2026, 11, 10))).isTrue();
        assertThat(m.festival(LocalDate.of(2026, 11, 11))).isFalse();
        assertThat(m.band(0.0)).isEqualTo("LOW");
        assertThat(m.band(0.35)).isEqualTo("MEDIUM");
        assertThat(m.band(0.7)).isEqualTo("HIGH");
        assertThat(m.band(1.0)).isEqualTo("HIGH");
    }

    @Test
    void a_model_file_that_does_not_match_this_code_fails_at_startup() throws IOException {
        ObjectNode doc = (ObjectNode) JSON.readTree(WaitlistModelTest.class.getResourceAsStream("/ml/wl-model.json"));

        ObjectNode reordered = doc.deepCopy();
        ArrayNode f = (ArrayNode) reordered.get("features");
        JsonNode first = f.remove(0);
        f.add(first);
        assertThatThrownBy(() -> load(reordered)).hasMessageContaining("model features");

        ObjectNode shortCoef = doc.deepCopy();
        ((ArrayNode) shortCoef.get("coef")).remove(0);
        assertThatThrownBy(() -> load(shortCoef)).hasMessageContaining("expected array");

        ObjectNode newVersion = doc.deepCopy();
        newVersion.put("featureVersion", 2);
        assertThatThrownBy(() -> load(newVersion)).hasMessageContaining("featureVersion");

        ObjectNode badMap = doc.deepCopy();
        badMap.set("isotonic", JSON.readTree("{\"x\":[0,0.5,1],\"y\":[0,0.8,0.6]}"));
        assertThatThrownBy(() -> load(badMap)).hasMessageContaining("not monotone");

        ObjectNode noClip = doc.deepCopy();
        ((ObjectNode) noClip.get("clip")).remove("wl_ratio");
        assertThatThrownBy(() -> load(noClip)).hasMessageContaining("clip");
    }

    @Test
    void the_calibration_map_interpolates_like_sklearn_and_clips_outside() throws IOException {
        ObjectNode doc = (ObjectNode) JSON.readTree(WaitlistModelTest.class.getResourceAsStream("/ml/wl-model.json"));
        doc.set("isotonic", JSON.readTree("{\"x\":[0.1,0.5,0.9],\"y\":[0.0,0.4,0.95]}"));
        WaitlistModel m = load(doc);
        assertThat(m.interpolate(0.0)).isEqualTo(0.0);
        assertThat(m.interpolate(0.3)).isCloseTo(0.2, within(1e-12));
        assertThat(m.interpolate(0.7)).isCloseTo(0.675, within(1e-12));
        assertThat(m.interpolate(1.0)).isEqualTo(0.95);
    }

    static WaitlistModel load(JsonNode doc) throws IOException {
        return WaitlistModel.load(new ByteArrayInputStream(JSON.writeValueAsBytes(doc)), JSON);
    }
}
