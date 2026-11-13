package in.railops.reservation.application;

import in.railops.reservation.domain.error.DomainException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PoolGateTest {

    @Test
    void never_more_than_permits_inside_one_pool() throws Exception {
        PoolGate gate = new PoolGate(3, Duration.ofSeconds(5), new SimpleMeterRegistry());
        AtomicInteger inside = new AtomicInteger(), peak = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            fs.add(pool.submit(() -> gate.through(1, "3A", "GN", () -> {
                peak.accumulateAndGet(inside.incrementAndGet(), Math::max);
                sleep(5);
                inside.decrementAndGet();
                return null;
            })));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        assertThat(peak.get()).isEqualTo(3);
    }

    @Test
    void pools_do_not_block_each_other() throws Exception {
        PoolGate gate = new PoolGate(1, Duration.ofMillis(200), new SimpleMeterRegistry());
        CountDownLatch holding = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread t = new Thread(() -> gate.through(1, "3A", "GN", () -> { holding.countDown(); await(release); return null; }));
        t.start();
        holding.await();
        assertThat(gate.through(1, "3A", "TQ", () -> "tatkal")).isEqualTo("tatkal");   // other quota: free
        assertThat(gate.through(2, "3A", "GN", () -> "other run")).isEqualTo("other run");
        release.countDown();
        t.join();
    }

    @Test
    void times_out_with_a_retryable_error_and_does_not_run_the_work() throws Exception {
        PoolGate gate = new PoolGate(1, Duration.ofMillis(100), new SimpleMeterRegistry());
        CountDownLatch holding = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread t = new Thread(() -> gate.through(1, "3A", "GN", () -> { holding.countDown(); await(release); return null; }));
        t.start();
        holding.await();
        AtomicInteger ran = new AtomicInteger();
        assertThatThrownBy(() -> gate.through(1, "3A", "GN", ran::incrementAndGet)).isInstanceOf(DomainException.class);
        assertThat(ran.get()).isZero();   // nothing booked: safe to retry with the same idempotency key
        release.countDown();
        t.join();
    }

    @Test
    void a_failing_booking_releases_its_permit() {
        PoolGate gate = new PoolGate(1, Duration.ofMillis(100), new SimpleMeterRegistry());
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> gate.through(1, "3A", "GN", () -> { throw new IllegalStateException("boom"); }))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(gate.through(1, "3A", "GN", () -> "still open")).isEqualTo("still open");
    }

    @Test
    void zero_permits_disables_the_gate() {
        assertThat(new PoolGate(0, Duration.ZERO, new SimpleMeterRegistry()).through(1, "3A", "GN", () -> 1)).isEqualTo(1);
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static void await(CountDownLatch l) {
        try { l.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
