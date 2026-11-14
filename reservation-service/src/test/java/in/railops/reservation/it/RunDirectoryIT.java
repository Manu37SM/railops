package in.railops.reservation.it;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Phase 5: public run lookup used by the RailLens website and app (seed run 1 = train 99001 on 2026-11-20). */
@AutoConfigureMockMvc
class RunDirectoryIT extends AbstractReservationIT {

    @Autowired MockMvc mvc;
    final ObjectMapper json = new JsonMapper();

    MockHttpServletResponse call(String url) throws Exception {
        return mvc.perform(get(url)).andReturn().getResponse();   // no token: must be public
    }

    @Test
    void finds_a_run_by_train_and_date_with_its_stops_and_classes() throws Exception {
        var r = call("/api/v1/runs?trainNo=99001&date=2026-11-20");
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        assertThat(r.getHeader("Cache-Control")).contains("max-age=60").contains("public");
        JsonNode b = json.readTree(r.getContentAsString());
        assertThat(b.get("runId").asLong()).isEqualTo(1);
        assertThat(b.get("trainNo").asString()).isEqualTo("99001");
        assertThat(b.get("runDate").asString()).isEqualTo("2026-11-20");
        assertThat(b.get("stops").get(0).get("seq").asInt()).isZero();
        assertThat(b.get("stops").get(0).get("stationCode").asString()).isEqualTo("MMCT");
        assertThat(b.get("stops").get(0).get("departure").asString()).isNotBlank();
        assertThat(b.get("classes").size()).isPositive();

        JsonNode byId = json.readTree(call("/api/v1/runs/1").getContentAsString());
        assertThat(byId.get("stops").size()).isEqualTo(b.get("stops").size());
    }

    @Test
    void unknown_runs_and_bad_input_are_clear_errors_not_500s() throws Exception {
        var none = call("/api/v1/runs?trainNo=99001&date=2026-11-21");
        assertThat(none.getStatus()).isEqualTo(404);
        assertThat(none.getContentAsString()).contains("RES_RUN_NOT_FOUND");
        assertThat(call("/api/v1/runs/999999").getStatus()).isEqualTo(404);
        assertThat(call("/api/v1/runs?trainNo=12;drop&date=2026-11-20").getStatus()).isEqualTo(400);
        assertThat(call("/api/v1/runs?trainNo=99001&date=tomorrow").getStatus()).isEqualTo(400);
        assertThat(call("/api/v1/runs?trainNo=99001").getStatus()).isEqualTo(400);
        assertThat(call("/api/v1/runs/abc/availability?from=0&to=1").getStatus()).isEqualTo(400);
    }
}
