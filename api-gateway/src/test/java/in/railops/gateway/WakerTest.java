package in.railops.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WakerTest {

    /** A service on localhost that answers 502 (host's "asleep" page) for the first {@code asleepFor} health checks. */
    private HttpServer server;
    private final AtomicInteger healthChecks = new AtomicInteger();
    private volatile int asleepFor;
    private volatile boolean neverWakes;
    private URI svc;
    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(Waker.HEALTH_PATH, ex -> {
            int n = healthChecks.incrementAndGet();
            boolean up = !neverWakes && n > asleepFor;
            byte[] body = (up ? "{\"status\":\"UP\"}" : "<html>502</html>").getBytes();
            ex.getResponseHeaders().add("Content-Type", up ? "application/json" : "text/html");
            ex.sendResponseHeaders(up ? 200 : 502, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        svc = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private Waker waker(boolean enabled, Duration maxWait) {
        Waker w = new Waker(enabled, List.of(URI.create(svc + "/api/v1/whatever")), WebClient.create(), clock,
                Duration.ofMinutes(5), Duration.ofMinutes(10), maxWait);
        w.retryDelay = Duration.ofMillis(50);
        return w;
    }

    @Test
    void offDoesNothing() {
        Waker w = waker(false, Duration.ofSeconds(5));
        w.awake(svc).block(Duration.ofSeconds(1));
        w.nudgeAll(true);
        assertThat(healthChecks).hasValue(0);
    }

    @Test
    void waitsForASleepingServiceThenRemembersItIsAwake() {
        asleepFor = 3;   // the host answers "502" three times while the service starts
        Waker w = waker(true, Duration.ofSeconds(5));
        w.awake(URI.create(svc + "/api/v1/bookings")).block(Duration.ofSeconds(5));
        assertThat(healthChecks).hasValue(4);

        w.awake(svc).block(Duration.ofSeconds(1));   // fresh: no new check
        assertThat(healthChecks).hasValue(4);

        clock.advance(Duration.ofMinutes(6));        // stale again: one more check
        w.awake(svc).block(Duration.ofSeconds(5));
        assertThat(healthChecks).hasValue(5);
    }

    @Test
    void concurrentRequestsShareOneProbe() {
        asleepFor = 2;
        Waker w = waker(true, Duration.ofSeconds(5));
        Mono.when(w.awake(svc), w.awake(svc), w.awake(svc)).block(Duration.ofSeconds(5));
        assertThat(healthChecks).hasValue(3);   // one probe: 2 failures + 1 success, not three probes
    }

    @Test
    void givesUpAfterMaxWaitAndLetsTheRequestThrough() {
        neverWakes = true;
        Waker w = waker(true, Duration.ofMillis(400));
        long t = System.nanoTime();
        w.awake(svc).block(Duration.ofSeconds(5));   // completes (empty), never errors
        assertThat(Duration.ofNanos(System.nanoTime() - t)).isLessThan(Duration.ofSeconds(3));
        assertThat(healthChecks.get()).isGreaterThan(1);
    }

    @Test
    void seenResponsesKeepItFreshWithoutHealthChecks() {
        Waker w = waker(true, Duration.ofSeconds(5));
        w.seen(URI.create(svc + "/api/v1/runs?x=1"));
        w.awake(svc).block(Duration.ofSeconds(1));
        assertThat(healthChecks).hasValue(0);
    }

    @Test
    void nudgesAtMostEveryTenMinutesUnlessForced() throws Exception {
        Waker w = waker(true, Duration.ofSeconds(5));
        w.nudgeAll();
        waitFor(1);
        clock.advance(Duration.ofMinutes(6));   // probe result is stale, but the nudge interval hasn't passed
        w.nudgeAll();
        Thread.sleep(200);
        assertThat(healthChecks).hasValue(1);

        clock.advance(Duration.ofMinutes(5));   // 11 minutes since the first nudge
        w.nudgeAll();
        waitFor(2);

        w.nudgeAll(true);                       // forced (gateway start), but the service was just seen: no probe
        Thread.sleep(200);
        assertThat(healthChecks).hasValue(2);
    }

    @Test
    void targetsAreServicesNotPaths() {
        Waker w = new Waker(true, List.of(URI.create("https://a.example/x"), URI.create("https://a.example/y"),
                URI.create("http://b.example:8080/z")), WebClient.create(), clock,
                Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofSeconds(1));
        assertThat(w.targets()).containsExactly(URI.create("https://a.example"), URI.create("http://b.example:8080"));
    }

    @Test
    void onlyTheHostsHtmlErrorPageDoesNotCountAsTheServiceAnswering() {
        assertThat(GatewayApplication.cameFromService(200, MediaType.APPLICATION_JSON)).isTrue();
        assertThat(GatewayApplication.cameFromService(401, null)).isTrue();
        assertThat(GatewayApplication.cameFromService(503, MediaType.APPLICATION_PROBLEM_JSON)).isTrue();   // the service's own 503
        assertThat(GatewayApplication.cameFromService(502, MediaType.TEXT_HTML)).isFalse();                // Render's page
        assertThat(GatewayApplication.cameFromService(0, null)).isFalse();
    }

    private void waitFor(int n) throws InterruptedException {
        for (int i = 0; i < 100 && healthChecks.get() < n; i++) {
            Thread.sleep(20);
        }
        assertThat(healthChecks).hasValue(n);
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-13T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
