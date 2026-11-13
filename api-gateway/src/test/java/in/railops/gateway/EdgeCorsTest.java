package in.railops.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.reactive.CorsWebFilter;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EdgeCorsTest {

    static MockServerWebExchange preflight(String origin, String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.options("https://railops-gateway.example" + path)
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type, Idempotency-Key"));
    }

    static MockServerWebExchange run(String configured, MockServerWebExchange ex) {
        new CorsWebFilter(EdgeCors.source(configured)).filter(ex, e -> Mono.empty()).block();
        return ex;
    }

    @Test
    void the_raillens_website_may_call_the_api() {
        var ex = run("https://raillens.vercel.app", preflight("https://raillens.vercel.app", "/api/v1/bookings"));
        HttpHeaders h = ex.getResponse().getHeaders();
        assertThat(ex.getResponse().getStatusCode()).as(h.toString()).isNotEqualTo(HttpStatus.FORBIDDEN);
        assertThat(h.getAccessControlAllowOrigin()).isEqualTo("https://raillens.vercel.app");
        assertThat(h.getAccessControlAllowMethods()).contains(HttpMethod.POST);
        assertThat(h.getAccessControlAllowCredentials()).isFalse();
    }

    @Test
    void any_other_site_is_refused() {
        var ex = run("https://raillens.vercel.app", preflight("https://evil.example", "/api/v1/bookings"));
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.getResponse().getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    void off_by_default() {
        var ex = run("", preflight("https://raillens.vercel.app", "/api/v1/bookings"));
        assertThat(ex.getResponse().getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    void wildcards_and_plain_http_are_refused_at_startup() {
        assertThatThrownBy(() -> EdgeCors.source("*")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> EdgeCors.source("https://*.vercel.app")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> EdgeCors.source("http://raillens.example")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> EdgeCors.source("https://raillens.vercel.app/")).isInstanceOf(IllegalStateException.class);
        EdgeCors.source("http://localhost:3000, https://raillens.vercel.app");   // fine
    }
}
