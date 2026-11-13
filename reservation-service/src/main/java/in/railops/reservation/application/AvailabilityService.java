package in.railops.reservation.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import in.railops.reservation.application.cache.AvailabilityCache;
import in.railops.reservation.application.cache.AvailabilityCache.CacheUnavailable;
import in.railops.reservation.application.cache.AvailabilityCache.PoolState;
import in.railops.reservation.application.cache.AvailabilityCache.RunState;
import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.error.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * Availability for one run and journey, per class and quota. Three tiers, cheapest first:
 * <ol>
 *   <li><b>In-process micro-cache</b> (500 ms) of the final answer per (run, from, to, class). During Tatkal
 *       thousands of people refresh the SAME search every second; recomputing it each time is pure waste, and half
 *       a second of extra staleness doesn't matter for an advisory answer.</li>
 *   <li><b>Redis read model</b>, one round trip per run (AvailabilityCache).</li>
 *   <li><b>PostgreSQL</b>, only through a small bulkhead when Redis is down: a Tatkal search storm must not become a
 *       query storm on the database that also has to take the bookings. Excess requests get a fast 503.</li>
 * </ol>
 * Results are advisory: booking re-checks under locks, so staleness can mislead but never oversell.
 */
@Service
public class AvailabilityService {

    public record PoolAvailability(String classCode, String quotaCode, int cnfFree, int racSlotsFree,
                                   int nextWlNumber, int wlRemaining, String display) {
    }

    public record RunAvailability(long trainRunId, String runStatus, int fromSeq, int toSeq,
                                  List<PoolAvailability> pools) {
    }

    private record Query(long runId, int from, int to, String cls) {
    }

    private final AvailabilityCache cache;
    private final Semaphore dbFallback;
    private final Cache<Query, RunAvailability> micro;
    private final boolean microEnabled;
    private final Counter fallbacks;
    private final Counter shed;
    private final boolean dbOnly;

    public AvailabilityService(AvailabilityCache cache, MeterRegistry meters,
                               @Value("${railops.availability.db-fallback-concurrency:8}") int fallbackConcurrency,
                               @Value("${railops.availability.micro-cache-ms:500}") long microCacheMs,
                               @Value("${railops.availability.source:redis}") String source) {
        this.cache = cache;
        this.dbOnly = "db".equals(source); // benchmarking/kill-switch only: bypasses Redis (no bulkhead)
        this.dbFallback = new Semaphore(fallbackConcurrency);
        // Bounded: a crawler enumerating every (run, from, to) can't grow the heap without limit.
        this.microEnabled = microCacheMs > 0;
        this.micro = Caffeine.newBuilder().maximumSize(50_000).expireAfterWrite(Duration.ofMillis(Math.max(1, microCacheMs))).build();
        this.fallbacks = Counter.builder("availability.db_fallback").register(meters);
        this.shed = Counter.builder("availability.shed").register(meters);
    }

    public RunAvailability forJourney(long runId, int fromSeq, int toSeq, String classCode) {
        if (fromSeq < 0 || toSeq <= fromSeq || toSeq - fromSeq > 200) {
            throw new DomainException(ErrorCode.RES_INVALID_JOURNEY, "invalid journey");
        }
        Query q = new Query(runId, fromSeq, toSeq, classCode);
        return microEnabled ? micro.get(q, k -> compute(k.runId(), k.from(), k.to(), k.cls()))
                : compute(runId, fromSeq, toSeq, classCode);
    }

    private RunAvailability compute(long runId, int fromSeq, int toSeq, String classCode) {
        RunState run;
        try {
            run = dbOnly ? cache.readRunFromDb(runId) : cache.run(runId);
        } catch (CacheUnavailable e) {
            if (!dbFallback.tryAcquire()) {
                shed.increment();
                throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE, "availability is temporarily unavailable");
            }
            try {
                fallbacks.increment();
                run = cache.readRunFromDb(runId);
            } finally {
                dbFallback.release();
            }
        }
        if (run == null) {
            throw new DomainException(ErrorCode.RES_RUN_NOT_FOUND, "train run not found");
        }
        List<PoolAvailability> out = new ArrayList<>();
        for (PoolState p : run.pools()) {
            if ((classCode != null && !p.classCode().equals(classCode)) || p.cnfFree().length < toSeq) {
                continue;
            }
            int cnf = Integer.MAX_VALUE;
            int rac = Integer.MAX_VALUE;
            for (int s = fromSeq; s < toSeq; s++) {
                cnf = Math.min(cnf, p.cnfFree()[s]);
                rac = Math.min(rac, p.racFree()[s]);
            }
            out.add(new PoolAvailability(p.classCode(), p.quotaCode(), cnf, rac, p.nextWl(), p.wlRemaining(),
                    display(run.status(), cnf, rac, p.nextWl(), p.wlRemaining())));
        }
        return new RunAvailability(runId, run.status(), fromSeq, toSeq, List.copyOf(out));
    }

    static String display(String runStatus, int cnf, int rac, int nextWl, int wlLeft) {
        if (!"SCHEDULED".equals(runStatus)) {
            return "BOOKING_CLOSED";
        }
        if (cnf > 0) {
            return "AVAILABLE-" + cnf;
        }
        if (rac > 0) {
            return "RAC-" + rac;
        }
        if (wlLeft > 0) {
            return "WL-" + nextWl;
        }
        return "REGRET";
    }
}
