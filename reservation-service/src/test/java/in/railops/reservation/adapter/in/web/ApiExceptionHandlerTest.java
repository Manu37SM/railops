package in.railops.reservation.adapter.in.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Client mistakes must not surface as 500s (found in the Render dry run, Phase 15+: malformed JSON, wrong content
 * type, wrong method and unknown paths all returned 500 and were logged as "unhandled error").
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void malformedJsonIsABadRequest() {
        var pd = handler.unreadable(new HttpMessageNotReadableException("bad", new MockHttpInputMessage(new byte[0])));
        assertThat(pd.getStatus()).isEqualTo(400);
        assertThat(pd.getProperties()).containsEntry("code", "MALFORMED_REQUEST");
    }

    @Test
    void springMvcRejectionsKeepTheirOwnStatus() {
        assertThat(handler.unexpected(new NoResourceFoundException(HttpMethod.GET, "actuator/prometheus"))
                .getStatus()).isEqualTo(404);
        assertThat(handler.unexpected(new HttpRequestMethodNotSupportedException("PUT", List.of("POST"))).getStatus())
                .isEqualTo(405);
        assertThat(handler.unexpected(new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN,
                List.of(MediaType.APPLICATION_JSON))).getStatus()).isEqualTo(415);
    }

    @Test
    void genuineBugsAreStill500() {
        assertThat(handler.unexpected(new IllegalStateException("boom")).getStatus()).isEqualTo(500);
    }
}
