package in.railops.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wakes the services behind the gateway on hosts that put idle services to sleep (Render's free plan: a service
 * sleeps after 15 idle minutes and takes about a minute to start).
 *
 * <ul>
 *   <li>{@link #nudgeAll()}: fire-and-forget health checks to every service. Called when the gateway starts and,
 *       while requests keep coming, at most every {@link #nudgeEvery}, so the services stay awake while the system is
 *       in use and all sleep together when it isn't.</li>
 *   <li>{@link #awake(URI)}: completes once the service has answered recently, waiting (up to {@link #maxWait}) for
 *       it to start if it hasn't. The gateway calls this BEFORE forwarding a request, so requests are never retried
 *       (no booking or payment is sent twice); they just wait for the service instead of getting the host's
 *       "service asleep" error. One probe per service at a time; concurrent requests share it.</li>
 * </ul>
 *
 * <p>Off (every method returns at once) unless enabled: compose and Kubernetes never sleep.
 */
public class Waker {

    private static final Logger log = LoggerFactory.getLogger(Waker.class);
    static final String HEALTH_PATH = "/actuator/health/readiness";

    private final boolean enabled;
    private final List<URI> targets;
    private final WebClient http;
    private final Clock clock;
    final Duration fresh;
    final Duration nudgeEvery;
    final Duration maxWait;
    Duration retryDelay = Duration.ofSeconds(3);

    private final Map<URI, Instant> lastSeen = new ConcurrentHashMap<>();
    private final Map<URI, Mono<Void>> inFlight = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> lastNudge = new AtomicReference<>(Instant.EPOCH);

    public Waker(boolean enabled, List<URI> targets, WebClient http, Clock clock,
                 Duration fresh, Duration nudgeEvery, Duration maxWait) {
        this.enabled = enabled;
        Set<URI> unique = new LinkedHashSet<>();
        for (URI t : targets) {
            unique.add(base(t));
        }
        this.targets = List.copyOf(unique);
        this.http = http;
        this.clock = clock;
        this.fresh = fresh;
        this.nudgeEvery = nudgeEvery;
        this.maxWait = maxWait;
    }

    public boolean enabled() {
        return enabled;
    }

    List<URI> targets() {
        return targets;
    }

    /** scheme://host[:port] of a URL: the service, not the path. */
    static URI base(URI u) {
        return URI.create(u.getScheme() + "://" + u.getRawAuthority());
    }

    /** Wakes every service, without waiting. At most once per {@link #nudgeEvery} unless {@code force}. */
    public void nudgeAll(boolean force) {
        if (!enabled) {
            return;
        }
        Instant now = clock.instant();
        Instant prev = lastNudge.get();
        if (!force && Duration.between(prev, now).compareTo(nudgeEvery) < 0) {
            return;
        }
        if (!lastNudge.compareAndSet(prev, now)) {
            return;   // another request is nudging
        }
        for (URI t : targets) {
            awake(t).subscribe(v -> { }, e -> { });
        }
    }

    public void nudgeAll() {
        nudgeAll(false);
    }

    /** The service answered (any response that came from the service itself): it's awake. */
    public void seen(URI target) {
        if (enabled && target != null) {
            lastSeen.put(base(target), clock.instant());
        }
    }

    /** Completes when the service is known to be awake, or after {@link #maxWait} (the request then goes ahead anyway). */
    public Mono<Void> awake(URI target) {
        if (!enabled || target == null) {
            return Mono.empty();
        }
        URI svc = base(target);
        Instant seen = lastSeen.get(svc);
        if (seen != null && Duration.between(seen, clock.instant()).compareTo(fresh) < 0) {
            return Mono.empty();
        }
        return inFlight.computeIfAbsent(svc, s -> probe(s)
                .doFinally(sig -> inFlight.remove(s))
                .cache());
    }

    private Mono<Void> probe(URI svc) {
        long started = System.nanoTime();
        return http.get().uri(svc.resolve(HEALTH_PATH))
                .retrieve()
                .toBodilessEntity()
                // A sleeping service may first answer with the host's error page: health checks are safe to repeat.
                .retryWhen(Retry.fixedDelay(Long.MAX_VALUE, retryDelay))
                .timeout(maxWait)
                .doOnSuccess(r -> {
                    lastSeen.put(svc, clock.instant());
                    long ms = (System.nanoTime() - started) / 1_000_000;
                    if (ms > 3000) {
                        log.info("{} is awake (took {} ms)", svc, ms);
                    }
                })
                .doOnError(e -> log.warn("{} did not answer its health check: {}", svc, e.toString()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }
}
