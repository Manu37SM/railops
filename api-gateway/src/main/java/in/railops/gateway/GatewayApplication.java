package in.railops.gateway;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The public edge. What it is NOT: the authorization layer. Every service still validates tokens and enforces
 * roles itself; the gateway only adds protection that is cheapest at the edge (blocking, throttling, headers).
 *
 * <p>Rate-limit keys: a VERIFIED user id when the request carries a valid token, else the client IP. Keying on an
 * unverified {@code sub} would let an attacker forge a victim's id and burn the victim's budget.
 *
 * <p>Limits fail OPEN if Redis is down (Spring Cloud Gateway's RedisRateLimiter behaviour): availability of the
 * edge beats perfect throttling, and the services' own protections (account lockout, waiting room fail-closed for
 * Tatkal, DB bulkheads) still hold.
 */
@SpringBootApplication
public class GatewayApplication {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    @Bean
    RouteLocator routes(RouteLocatorBuilder b, @Qualifier("principalKey") KeyResolver principalKey,
                        @Value("${railops.routes.identity}") String identity,
                        @Value("${railops.routes.reservation}") String reservation,
                        @Value("${railops.routes.payment}") String payment) {
        return b.routes()
                // ---- identity: login and registration throttled per IP (brute force / enumeration)
                .route("auth-credentials", r -> r.method(HttpMethod.POST).and().path("/api/v1/auth/login", "/api/v1/auth/app/login", "/api/v1/auth/register")
                        .filters(f -> f.requestRateLimiter(c -> c.setRateLimiter(credentialLimiter()).setKeyResolver(ipKey())))
                        .uri(identity))
                // Staff "move RailLens accounts": identity waits up to 120 s for RailLens (it may be asleep on a free
                // plan), so this one route may take longer than the 10 s every other route gets.
                .route("raillens-account-move", r -> r.method(HttpMethod.POST).and().path("/api/v1/admin/raillens/account-move")
                        .filters(f -> f.requestRateLimiter(c -> c.setRateLimiter(defaultLimiter()).setKeyResolver(principalKey)))
                        .metadata(org.springframework.cloud.gateway.support.RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, 130_000)
                        .uri(identity))
                .route("identity", r -> r.path("/api/v1/auth/**", "/api/v1/admin/**", "/.well-known/**")
                        .filters(f -> f.requestRateLimiter(c -> c.setRateLimiter(defaultLimiter()).setKeyResolver(principalKey)))
                        .uri(identity))
                // ---- payment gateway webhooks (HMAC-authenticated by payment-service)
                .route("webhooks", r -> r.path("/webhooks/**").uri(payment))
                // ---- reservation
                .route("booking-create", r -> r.method(HttpMethod.POST).and().path("/api/v1/bookings")
                        .filters(f -> f.requestRateLimiter(c -> c.setRateLimiter(bookingLimiter()).setKeyResolver(principalKey)))
                        .uri(reservation))
                .route("availability", r -> r.method(HttpMethod.GET).and().path("/api/v1/runs/*/availability", "/api/v1/runs/*/seat-map", "/api/v1/runs", "/api/v1/runs/*")
                        .filters(f -> f.requestRateLimiter(c -> c.setRateLimiter(searchLimiter()).setKeyResolver(ipKey())))
                        .uri(reservation))
                .route("reservation", r -> r.path("/api/**")
                        .filters(f -> f.requestRateLimiter(c -> c.setRateLimiter(defaultLimiter()).setKeyResolver(principalKey)))
                        .uri(reservation))
                .build();
    }

    // Token bucket per key: replenishRate tokens/s, burstCapacity bucket size. Starting points, to be tuned from
    // production metrics (gateway_requests_seconds + 429 counts).
    @Bean
    @Primary
    RedisRateLimiter defaultLimiter() {
        return new RedisRateLimiter(20, 40, 1);
    }

    @Bean
    RedisRateLimiter credentialLimiter() {
        return new RedisRateLimiter(1, 5, 1);      // 5 attempts, then 1/s per IP
    }

    @Bean
    RedisRateLimiter bookingLimiter() {
        return new RedisRateLimiter(1, 3, 1);      // a human doesn't create more than a few bookings a second
    }

    @Bean
    RedisRateLimiter searchLimiter() {
        return new RedisRateLimiter(10, 30, 1);
    }

    /** See {@link Waker}. On only where services sleep (Render's free plan): {@code RAILOPS_EDGE_WAKE_ENABLED=true}. */
    @Bean
    Waker waker(@Value("${railops.edge.wake.enabled:false}") boolean enabled,
                @Value("${railops.edge.wake.extra:}") String extra,
                @Value("${railops.routes.identity}") String identity,
                @Value("${railops.routes.reservation}") String reservation,
                @Value("${railops.routes.payment}") String payment) {
        List<URI> targets = new ArrayList<>(List.of(URI.create(identity), URI.create(reservation), URI.create(payment)));
        for (String e : extra.split(",")) {
            if (!e.isBlank()) {
                targets.add(URI.create(e.trim()));
            }
        }
        return new Waker(enabled, targets, WebClient.create(), Clock.systemUTC(),
                Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofSeconds(75));
    }

    @EventListener(ApplicationReadyEvent.class)
    void wakeEverythingOnStart(ApplicationReadyEvent event) {
        Waker w = event.getApplicationContext().getBean(Waker.class);
        if (w.enabled()) {
            w.nudgeAll(true);   // the gateway woke up: wake the services now, in parallel, before the first request needs them
        }
    }

    /**
     * Runs after the route is chosen and before the request is forwarded: waits for the target service if it may be
     * asleep (see {@link Waker}), and keeps all services awake while requests keep coming. A response that came from
     * the service itself (not the host's own HTML error page) marks it awake.
     */
    @Bean
    @Order(10_001)   // after RouteToRequestUrlFilter (10000), before NettyRoutingFilter (forwarding)
    GlobalFilter wakeBeforeForwarding(Waker waker) {
        return (exchange, chain) -> {
            if (!waker.enabled()) {
                return chain.filter(exchange);
            }
            URI target = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
            waker.nudgeAll();
            return waker.awake(target)
                    .then(chain.filter(exchange))
                    .doOnSuccess(v -> {
                        if (cameFromService(exchange.getResponse().getStatusCode() == null ? 0
                                        : exchange.getResponse().getStatusCode().value(),
                                exchange.getResponse().getHeaders().getContentType())) {
                            waker.seen(target);
                        }
                    });
        };
    }

    /** A 5xx HTML page is the host's own error (e.g. Render's "service unavailable"), not an answer from the service. */
    static boolean cameFromService(int status, MediaType type) {
        if (status == 0) {
            return false;
        }
        if (status < 500) {
            return true;
        }
        return type == null || !MediaType.TEXT_HTML.isCompatibleWith(type);
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(@Value("${railops.security.jwk-set-uri}") String jwks) {
        return NimbusReactiveJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
    }

    static final String CLIENT_IP_ATTR = "railops.clientIp";

    @Bean
    ClientIp clientIpResolver(@Value("${railops.edge.trusted-proxies:}") String trusted,
                              @Value("${railops.edge.client-ip-header:}") String clientIpHeader,
                              @Value("${railops.edge.proxy-secret:}") String proxySecret) {
        return new ClientIp(trusted, clientIpHeader, proxySecret);
    }

    /** Client IP as resolved once per request by {@link #edgeHeaders}. */
    static String clientIp(ServerWebExchange exchange) {
        String ip = exchange.getAttribute(CLIENT_IP_ATTR);
        return ip != null ? ip : "unknown";
    }

    @Bean
    @Primary
    KeyResolver ipKey() {
        return exchange -> Mono.just("ip:" + clientIp(exchange));
    }

    /** Verified subject if the bearer token validates, otherwise the client IP. */
    @Bean
    KeyResolver principalKey(ReactiveJwtDecoder decoder) {
        return exchange -> {
            String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            String ip = "ip:" + clientIp(exchange);
            if (auth == null || !auth.startsWith("Bearer ")) {
                return Mono.just(ip);
            }
            return decoder.decode(auth.substring(7))
                    .map(jwt -> "user:" + jwt.getSubject())
                    .onErrorReturn(ip);
        };
    }


    /**
     * Paths that must never be reachable from the internet. Checked on the DECODED, normalised path segments so
     * "/api/v1/../internal/x", "/INTERNAL/x" or "%2Finternal" tricks don't slip past a string prefix match.
     */
    static boolean blocked(ServerHttpRequest request) {
        for (var element : request.getPath().pathWithinApplication().elements()) {
            if (element instanceof org.springframework.http.server.PathContainer.PathSegment seg) {
                String v = seg.valueToMatch().toLowerCase(java.util.Locale.ROOT);
                if (v.equals("internal") || v.equals("actuator") || v.equals("oauth2") || v.equals("sim-gateway") || v.equals("..")) {
                    return true;
                }
            }
        }
        return false;
    }

    static String remoteIp(ServerWebExchange exchange) {
        var a = exchange.getRequest().getRemoteAddress();
        return a == null || a.getAddress() == null ? "unknown" : a.getAddress().getHostAddress();
    }

    /** Correlation id (accept a sane inbound one, else mint) + security headers on every response. */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    GlobalFilter edgeHeaders(ClientIp resolver) {
        return (exchange, chain) -> {
            String client = resolver.resolve(exchange.getRequest());
            exchange.getAttributes().put(CLIENT_IP_ATTR, client);
            String inbound = exchange.getRequest().getHeaders().getFirst("X-Correlation-Id");
            String id = inbound != null && SAFE_ID.matcher(inbound).matches() ? inbound : UUID.randomUUID().toString();
            if (blocked(exchange.getRequest())) {
                exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                exchange.getResponse().getHeaders().set("X-Correlation-Id", id);
                return exchange.getResponse().setComplete();
            }
            // Downstream sees exactly ONE forwarding fact, set here. The client's own Forwarded/X-Forwarded-* headers
            // are dropped (SCG's own forwarded-header filters are disabled in application.yml).
            ServerWebExchange ex = exchange.mutate().request(r -> r.headers(h -> {
                h.set("X-Correlation-Id", id);
                h.remove("Forwarded");
                h.remove("X-Forwarded-Host");
                h.remove("X-Forwarded-Port");
                h.remove("X-Forwarded-Prefix");
                if (!resolver.trustedHeader().isEmpty()) {
                    h.remove(resolver.trustedHeader());   // downstream sees only the X-Forwarded-For set below
                }
                h.remove(ClientIp.PROXY_SECRET_HEADER);     // never forwarded, whatever its value
                h.remove(ClientIp.PROXY_CLIENT_IP_HEADER);
                h.set("X-Forwarded-For", client);
                String proto = resolver.isTrusted(remoteIp(exchange)) ? exchange.getRequest().getHeaders().getFirst("X-Forwarded-Proto") : null;
                h.set("X-Forwarded-Proto", "https".equals(proto) ? "https" : exchange.getRequest().getURI().getScheme());
            })).build();
            ex.getResponse().beforeCommit(() -> {
                HttpHeaders h = ex.getResponse().getHeaders();
                h.set("X-Correlation-Id", id);
                h.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
                h.set("X-Content-Type-Options", "nosniff");
                h.set("Referrer-Policy", "no-referrer");
                h.remove("X-Powered-By");
                return Mono.empty();
            });
            return chain.filter(ex);
        };
    }
}
