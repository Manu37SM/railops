package in.railops.reservation.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.error.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Caps concurrent allocation transactions per inventory pool (run, class, quota) on this instance.
 *
 * <p>Why (Phase 10 measurement, scripts/bench_booking.py): a pool's counter rows serialise every booking, so
 * throughput = 1 / lock-hold time. Uncontended hold was 7 ms (ceiling ~140/s); with 32 concurrent bookers the hold
 * stretched to 14.5 ms and throughput FELL from 88/s to 77/s, because the lock holder competes for CPU and
 * connections with everyone queued behind it. Queuing here instead costs nothing: no connection, no transaction, no
 * pre-lock work done twice.
 *
 * <p>Per instance, not global: N instances admit N x permits. That's fine because the goal is to keep the queue out of
 * PostgreSQL, not to be exact; the waiting room already paces arrivals globally.
 *
 * <p>Fairness: Semaphore(fair=true), so a request isn't overtaken by later arrivals on this instance.
 */
@Component
public class PoolGate {

    private final int permits;
    private final Duration maxWait;
    private final Cache<String, Semaphore> gates = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10)).maximumSize(100_000).build();
    private final Counter rejected;

    public PoolGate(@Value("${railops.booking.pool-concurrency:4}") int permits,
                    @Value("${railops.booking.pool-max-wait:PT3S}") Duration maxWait, MeterRegistry meters) {
        this.permits = permits;
        this.maxWait = maxWait;
        this.rejected = Counter.builder("inventory.pool_gate.rejected")
                .description("bookings refused after waiting maxWait for a pool slot").register(meters);
    }

    public <T> T through(long runId, String cls, String quota, Supplier<T> work) {
        if (permits <= 0) {
            return work.get();   // disabled
        }
        // Eviction of an in-use semaphore is harmless: a new one is created for later callers and the old one is
        // released by its holders; the cap is briefly exceeded, never deadlocked.
        Semaphore gate = gates.get(runId + ":" + cls + ":" + quota, k -> new Semaphore(permits, true));
        boolean acquired;
        try {
            acquired = gate.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE, "interrupted while waiting for inventory");
        }
        if (!acquired) {
            rejected.increment();
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "this train is very busy right now; nothing was booked, please retry");
        }
        try {
            return work.get();
        } finally {
            gate.release();
        }
    }
}
