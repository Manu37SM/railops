package in.railops.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Lets the RailLens website (a different origin, e.g. https://rail-lens-frontend.vercel.app) call the RailOps API from the
 * browser (Phase 3B, needed by booking in Phase 4). The RailOps Angular app is same-origin and needs none of this.
 *
 * <p>Off unless {@code railops.edge.cors-allowed-origins} lists origins. Exact origins only: a wildcard is refused at
 * startup. No credentials (cookies): RailOps uses bearer tokens, so other sites can't ride a user's session.
 */
@Configuration
class EdgeCors {

    static final List<String> ALLOWED_HEADERS = List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id");
    static final List<String> EXPOSED_HEADERS = List.of("X-Correlation-Id", "Retry-After", "Location");

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    CorsWebFilter edgeCorsFilter(@Value("${railops.edge.cors-allowed-origins:}") String origins) {
        return new CorsWebFilter(source(origins));
    }

    static UrlBasedCorsConfigurationSource source(String origins) {
        List<String> list = Arrays.stream(origins == null ? new String[0] : origins.split("[,\\s]+"))
                .map(String::trim).filter(s -> !s.isEmpty()).map(EdgeCors::checked).toList();
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (list.isEmpty()) {
            return source;   // no mapping: cross-origin browser calls get no CORS headers and are blocked
        }
        CorsConfiguration api = new CorsConfiguration();
        api.setAllowedOrigins(list);
        api.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
        api.setAllowedHeaders(ALLOWED_HEADERS);
        api.setExposedHeaders(EXPOSED_HEADERS);
        api.setAllowCredentials(false);
        api.setMaxAge(Duration.ofMinutes(10));
        source.registerCorsConfiguration("/api/**", api);
        return source;
    }

    private static String checked(String origin) {
        boolean local = origin.startsWith("http://localhost:") || origin.equals("http://localhost");
        if (origin.contains("*") || !(origin.startsWith("https://") || local) || origin.endsWith("/")) {
            throw new IllegalStateException("railops.edge.cors-allowed-origins: '" + origin
                    + "' must be an exact https:// origin (or http://localhost:<port>) with no path, slash or wildcard");
        }
        return origin;
    }
}
