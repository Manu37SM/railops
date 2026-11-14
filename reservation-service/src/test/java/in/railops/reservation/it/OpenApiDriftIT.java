package in.railops.reservation.it;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The hand-written contract (docs/openapi/reservation-v1.yaml, what clients code against) and the running service
 * must expose the same operations. Catches an endpoint added without documentation, or documented but removed.
 */
@AutoConfigureMockMvc
class OpenApiDriftIT extends AbstractReservationIT {

    @Autowired MockMvc mvc;

    static Set<String> operations(JsonNode spec) {
        Set<String> ops = new TreeSet<>();
        spec.path("paths").properties().iterator().forEachRemaining(p -> p.getValue().propertyNames().iterator().forEachRemaining(m -> {
            if (Set.of("get", "post", "put", "patch", "delete").contains(m)) {
                ops.add(m.toUpperCase() + " " + p.getKey().replaceAll("\\{[^}]+}", "{}"));
            }
        }));
        return ops;
    }

    @Test
    void documented_and_implemented_public_operations_match() throws Exception {
        String live = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
        Set<String> implemented = operations(new JsonMapper().readTree(live));
        implemented.removeIf(o -> o.contains("/internal/"));   // service-to-service: contracts/http, not public docs
        Set<String> documented = operations(new YAMLMapper().readTree(
                Path.of("..", "docs", "openapi", "reservation-v1.yaml").toFile()));

        Set<String> undocumented = new TreeSet<>(implemented);
        undocumented.removeAll(documented);
        Set<String> phantom = new TreeSet<>(documented);
        phantom.removeAll(implemented);
        assertThat(undocumented).as("implemented but not in reservation-v1.yaml").isEmpty();
        assertThat(phantom).as("documented but not implemented").isEmpty();
    }
}
