package in.railops.reservation.adapter.out.http;

import in.railops.reservation.application.fare.FarePort;
import in.railops.reservation.application.fare.FareQuote;
import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.error.ErrorCode;
import in.railops.reservation.domain.model.Quota;
import in.railops.reservation.domain.model.TravelClass;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * catalog-service fare quotes. Fares change by circular, not by the minute, so a 10-minute
 * cache absorbs Tatkal-hour load and keeps booking working through a short catalog outage.
 *
 * <p>Resilience4j aspect order (default): Retry wraps CircuitBreaker, so each retry attempt is
 * recorded by the breaker. The fallback serves a stale cached quote if one exists. A stale fare is
 * acceptable for at most the cache window plus outage; with no cached quote we fail rather than guess.
 */
@Component
@Profile("!sim")
public class HttpFareClient implements FarePort {

    private record Key(String trainNo, TravelClass cls, Quota quota, int distanceKm) {
    }

    private record Entry(FareQuote quote, Instant fetchedAt) {
    }

    private static final Duration FRESH_FOR = Duration.ofMinutes(10);

    private final RestClient client;
    private final ConcurrentHashMap<Key, Entry> cache = new ConcurrentHashMap<>();

    public HttpFareClient(RestClient.Builder builder, @Value("${railops.catalog.base-url}") String baseUrl) {
        var rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofMillis(200));
        rf.setReadTimeout(Duration.ofMillis(300));
        this.client = builder.baseUrl(baseUrl).requestFactory(rf).build();
    }

    @Override
    @Retry(name = "catalog")
    @CircuitBreaker(name = "catalog", fallbackMethod = "staleOrFail")
    public FareQuote quote(String trainNo, TravelClass cls, Quota quota, int distanceKm) {
        Key key = new Key(trainNo, cls, quota, distanceKm);
        Entry cached = cache.get(key);
        if (cached != null && cached.fetchedAt().plus(FRESH_FOR).isAfter(Instant.now())) {
            return cached.quote();
        }
        FareQuote fresh = client.get()
                .uri(u -> u.path("/internal/v1/fares")
                        .queryParam("trainNo", trainNo).queryParam("class", cls.code)
                        .queryParam("quota", quota.name()).queryParam("distanceKm", distanceKm).build())
                .accept(org.springframework.http.MediaType.APPLICATION_JSON)
                .retrieve()
                .body(FareQuote.class);
        if (fresh == null || fresh.perPassenger() == null || fresh.perPassenger().signum() <= 0) {
            throw new IllegalStateException("catalog returned an invalid fare for " + key);
        }
        cache.put(key, new Entry(fresh, Instant.now()));
        return fresh;
    }

    @SuppressWarnings("unused")
    private FareQuote staleOrFail(String trainNo, TravelClass cls, Quota quota, int distanceKm, Throwable cause) {
        Entry cached = cache.get(new Key(trainNo, cls, quota, distanceKm));
        if (cached != null) {
            return cached.quote();
        }
        throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE, "fare service unavailable");
    }
}
