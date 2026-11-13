package in.railops.reservation.application.cache;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Redis read model for availability: ONE hash per run, read with ONE round trip.
 *
 * <pre>
 * ro:run:{runId}  HASH
 *   status        SCHEDULED | CHART_1 | ... | CANCELLED
 *   pools         "3A:GN,3A:TQ"                      (presence = the run is fully loaded)
 *   p:3A:GN       "200,200,198,200;20,20,20,20;1;100"  cnfFree per seg ; racFree per seg ; nextWl ; wlRemaining
 * </pre>
 *
 * Revision of the first Phase 10 version (a key per pool + a status key + a pool-set key = 3 round trips per
 * search), which measured no faster than PostgreSQL on a CPU-bound box. See docs/performance.md.
 *
 * <ul>
 *   <li>Writes are full-pool recomputes from PostgreSQL (one HSET), never deltas: redelivered or reordered events
 *       can't corrupt the model, and each update heals earlier drift.</li>
 *   <li>Cold runs load single-flight per run per instance.</li>
 *   <li>TTL 6 h ± 30 min jitter on the whole key: the self-repair path if an invalidation is ever missed.</li>
 *   <li>Redis errors surface as {@link CacheUnavailable} behind a circuit breaker; the caller degrades to the DB.</li>
 * </ul>
 */
@Component
public class AvailabilityCache {

    private static final Duration TTL = Duration.ofHours(6);

    public record PoolState(String classCode, String quotaCode, int[] cnfFree, int[] racFree, int nextWl, int wlRemaining) {
    }

    public record RunState(String status, List<PoolState> pools) {
    }

    public static final class CacheUnavailable extends RuntimeException {
        CacheUnavailable(Throwable cause) {
            super("Redis unavailable", cause, false, false);
        }
    }

    private final StringRedisTemplate redis;
    private final JdbcClient jdbc;
    private final CircuitBreaker breaker;
    private final ConcurrentHashMap<Long, CompletableFuture<RunState>> inflight = new ConcurrentHashMap<>();
    private final Counter hits;
    private final Counter misses;

    public AvailabilityCache(StringRedisTemplate redis, JdbcClient jdbc, MeterRegistry meters, CircuitBreakerRegistry breakers) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.breaker = breakers.circuitBreaker("redis");
        this.hits = Counter.builder("availability.cache").tag("result", "hit").register(meters);
        this.misses = Counter.builder("availability.cache").tag("result", "miss").register(meters);
    }

    /** @return null if the run doesn't exist. Throws CacheUnavailable if Redis can't be used. */
    public RunState run(long runId) {
        Map<Object, Object> h = guarded(() -> redis.opsForHash().entries(key(runId)));
        if (h.containsKey("status") && h.containsKey("pools")) {
            List<PoolState> pools = new ArrayList<>();
            boolean complete = true;
            for (String p : ((String) h.get("pools")).split(",")) {
                if (p.isEmpty()) {
                    continue;
                }
                Object enc = h.get("p:" + p);
                if (enc == null) {
                    complete = false;
                    break;
                }
                String[] cq = p.split(":");
                pools.add(decode(cq[0], cq[1], (String) enc));
            }
            if (complete) {
                hits.increment();
                return new RunState((String) h.get("status"), pools);
            }
        }
        misses.increment();
        return loadSingleFlight(runId);
    }

    /** Recompute one pool from PostgreSQL and write its field. Used by the event consumer. */
    public void refreshPool(long runId, String cls, String quota) {
        PoolState s = readPool(runId, cls, quota);
        guarded(() -> {
            if (Boolean.TRUE.equals(redis.hasKey(key(runId)))) {
                // Only patch runs that are already loaded; a cold run loads completely on its next read.
                redis.opsForHash().put(key(runId), "p:" + cls + ":" + quota, encode(s));
            }
            return null;
        });
    }

    public void refreshStatus(long runId) {
        String status = jdbc.sql("SELECT status FROM reservation.train_run WHERE id = :id").param("id", runId)
                .query(String.class).optional().orElse(null);
        guarded(() -> {
            if (status != null && Boolean.TRUE.equals(redis.hasKey(key(runId)))) {
                redis.opsForHash().put(key(runId), "status", status);
            }
            return null;
        });
    }

    /** Direct DB read of a whole run (benchmark baseline, and the degraded path when Redis is down). */
    public RunState readRunFromDb(long runId) {
        String status = jdbc.sql("SELECT status FROM reservation.train_run WHERE id = :id").param("id", runId)
                .query(String.class).optional().orElse(null);
        if (status == null) {
            return null;
        }
        List<PoolState> pools = new ArrayList<>();
        for (String p : jdbc.sql("SELECT DISTINCT class_code || ':' || quota_code FROM reservation.segment_inventory WHERE train_run_id = :r ORDER BY 1")
                .param("r", runId).query(String.class).list()) {
            String[] cq = p.split(":");
            pools.add(readPool(runId, cq[0], cq[1]));
        }
        return new RunState(status, pools);
    }

    // ------------------------------------------------------------------ internals

    private RunState loadSingleFlight(long runId) {
        CompletableFuture<RunState> mine = new CompletableFuture<>();
        CompletableFuture<RunState> existing = inflight.putIfAbsent(runId, mine);
        if (existing != null) {
            return existing.join();
        }
        try {
            RunState s = readRunFromDb(runId);
            if (s != null) {
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("status", s.status());
                fields.put("pools", String.join(",", s.pools().stream().map(p -> p.classCode() + ":" + p.quotaCode()).toList()));
                s.pools().forEach(p -> fields.put("p:" + p.classCode() + ":" + p.quotaCode(), encode(p)));
                try {
                    guarded(() -> {
                        redis.opsForHash().putAll(key(runId), fields);
                        redis.expire(key(runId), TTL.plusSeconds(ThreadLocalRandom.current().nextLong(-1800, 1800)));
                        return null;
                    });
                } catch (CacheUnavailable ignored) {
                    // still return the DB answer
                }
            }
            mine.complete(s);
            return s;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(runId, mine);
        }
    }

    private PoolState readPool(long runId, String cls, String quota) {
        List<int[]> rows = jdbc.sql("""
                        SELECT seg_idx, cnf_capacity - cnf_used, rac_capacity - rac_used FROM reservation.segment_inventory
                        WHERE train_run_id = :r AND class_code = :c AND quota_code = :q ORDER BY seg_idx
                        """).param("r", runId).param("c", cls).param("q", quota)
                .query((rs, n) -> new int[]{rs.getInt(1), rs.getInt(2), rs.getInt(3)}).list();
        int segs = rows.isEmpty() ? 0 : rows.getLast()[0] + 1;
        int[] cnf = new int[segs];
        int[] rac = new int[segs];
        rows.forEach(r -> {
            cnf[r[0]] = r[1];
            rac[r[0]] = r[2];
        });
        int[] wl = jdbc.sql("SELECT next_seq, wl_cap - next_seq + 1 FROM reservation.waitlist_counter WHERE train_run_id = :r AND class_code = :c AND quota_code = :q")
                .param("r", runId).param("c", cls).param("q", quota)
                .query((rs, n) -> new int[]{rs.getInt(1), rs.getInt(2)}).optional().orElse(new int[]{1, 0});
        return new PoolState(cls, quota, cnf, rac, wl[0], Math.max(0, wl[1]));
    }

    static String encode(PoolState s) {
        return join(s.cnfFree()) + ";" + join(s.racFree()) + ";" + s.nextWl() + ";" + s.wlRemaining();
    }

    static PoolState decode(String cls, String quota, String enc) {
        String[] parts = enc.split(";", -1);
        return new PoolState(cls, quota, ints(parts[0]), ints(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
    }

    private static String join(int[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(a[i]);
        }
        return sb.toString();
    }

    private static int[] ints(String csv) {
        return csv.isEmpty() ? new int[0] : Arrays.stream(csv.split(",")).mapToInt(Integer::parseInt).toArray();
    }

    private static String key(long runId) {
        return "ro:run:" + runId;
    }

    private <T> T guarded(Supplier<T> op) {
        try {
            return breaker.executeSupplier(op);
        } catch (CallNotPermittedException | DataAccessException e) {
            throw new CacheUnavailable(e);
        }
    }
}
