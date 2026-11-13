package in.railops.reservation.application;

import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.error.ErrorCode;
import in.railops.reservation.domain.model.Quota;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Tatkal waiting room (Phase 1 missing requirement #3: fairness at open time).
 *
 * <p><b>Fairness rule.</b> Everyone who joins BEFORE {@code opensAt} gets a uniformly random place among the early
 * joiners; everyone after joins FIFO behind them. Arriving 50 ms before a bot buys a human nothing, and arriving
 * 50 ms before a human buys a bot nothing. Speed stops being the advantage.
 *
 * <pre>
 * ro:wr:{run}:cfg            HASH  opensAt (epoch ms), rate (admissions/s)
 * ro:wr:active               SET   run ids with a live room
 * ro:wr:{run}:q              ZSET  userId -> score  (early: opensAt*1000 - 1e9 + rand(1e9); late: joinMs*1000)
 * ro:wr:{run}:adm:{userId}   STRING admission, 10 min TTL
 * </pre>
 * The {run} braces are a Redis Cluster hash tag: all keys of one room share a slot, so the admission Lua script
 * stays valid on a cluster.
 *
 * <p><b>Failure policy.</b> If Redis is down while a room might be active: Tatkal bookings fail CLOSED (503). A
 * Redis outage at 10:00 must not release the full stampede onto the booking database. Other quotas fail open.
 */
@Service
public class WaitingRoomService {

    private static final Logger log = LoggerFactory.getLogger(WaitingRoomService.class);
    static final Duration ADMISSION_TTL = Duration.ofMinutes(10);

    /** ZPOPMIN + grant admissions atomically: a crash between the two can't drop people from the queue. */
    private static final DefaultRedisScript<Long> ADMIT = new DefaultRedisScript<>("""
            local popped = redis.call('ZPOPMIN', KEYS[1], ARGV[1])
            for i = 1, #popped, 2 do
              redis.call('SET', ARGV[2] .. popped[i], '1', 'EX', ARGV[3])
            end
            return #popped / 2
            """, Long.class);

    public enum State { NOT_REQUIRED, NOT_JOINED, QUEUED, ADMITTED }

    static final long LOTTERY_SPAN = 1_000_000_000L;

    /**
     * Queue score. Early (now &lt; opensAt): a random draw in [opensAt·1000 − SPAN, opensAt·1000 − 1]. Late:
     * now·1000, which is ≥ opensAt·1000. Integer arithmetic, and |score| &lt; 2^53, so every value is exactly
     * representable as the ZSET's double: an early joiner can never tie with or pass a late joiner. (The Phase 10
     * version added a random DOUBLE, which could round up to opensAt·1000.)
     */
    static long score(long nowMs, long opensAtMs, long draw) {
        if (draw < 0 || draw >= LOTTERY_SPAN) {
            throw new IllegalArgumentException("draw out of range");
        }
        return nowMs < opensAtMs ? opensAtMs * 1000 - LOTTERY_SPAN + draw : nowMs * 1000;
    }

    public record Status(State state, Long position, Long etaSeconds, Instant opensAt) {
    }

    private final StringRedisTemplate redis;
    private final MeterRegistry meters;
    private final String instanceId = UUID.randomUUID().toString();

    public WaitingRoomService(StringRedisTemplate redis, MeterRegistry meters) {
        this.redis = redis;
        this.meters = meters;
    }

    public void open(long runId, Instant opensAt, int admitPerSecond) {
        if (admitPerSecond < 1 || admitPerSecond > 10_000) {
            throw new IllegalArgumentException("admitPerSecond out of range");
        }
        redis.opsForHash().putAll(cfg(runId), Map.of("opensAt", Long.toString(opensAt.toEpochMilli()),
                "rate", Integer.toString(admitPerSecond)));
        redis.expire(cfg(runId), Duration.ofDays(2));
        redis.opsForSet().add("ro:wr:active", Long.toString(runId));
    }

    public void close(long runId) {
        redis.opsForSet().remove("ro:wr:active", Long.toString(runId));
        redis.delete(List.of(cfg(runId), queue(runId)));
    }

    public Status join(long runId, UUID userId) {
        Map<Object, Object> c = redis.opsForHash().entries(cfg(runId));
        if (c.isEmpty()) {
            return new Status(State.NOT_REQUIRED, null, null, null);
        }
        long opensAt = Long.parseLong((String) c.get("opensAt"));
        long now = System.currentTimeMillis();
        double score = score(now, opensAt, ThreadLocalRandom.current().nextLong(LOTTERY_SPAN));                                                       // FIFO after opening
        if (!Boolean.TRUE.equals(redis.hasKey(admitted(runId, userId)))) {
            redis.opsForZSet().addIfAbsent(queue(runId), userId.toString(), score);  // NX: re-joining keeps the place
        }
        return status(runId, userId);
    }

    public Status status(long runId, UUID userId) {
        Map<Object, Object> c = redis.opsForHash().entries(cfg(runId));
        if (c.isEmpty()) {
            return new Status(State.NOT_REQUIRED, null, null, null);
        }
        Instant opensAt = Instant.ofEpochMilli(Long.parseLong((String) c.get("opensAt")));
        if (Boolean.TRUE.equals(redis.hasKey(admitted(runId, userId)))) {
            return new Status(State.ADMITTED, 0L, 0L, opensAt);
        }
        Long rank = redis.opsForZSet().rank(queue(runId), userId.toString());
        if (rank == null) {
            return new Status(State.NOT_JOINED, null, null, opensAt);
        }
        long rate = Long.parseLong((String) c.get("rate"));
        long untilOpen = Math.max(0, Duration.between(Instant.now(), opensAt).toSeconds());
        return new Status(State.QUEUED, rank + 1, untilOpen + (rank / rate), opensAt);
    }

    /** Booking gate. Called before the booking transaction. */
    public void requireAdmission(long runId, UUID userId, Quota quota) {
        try {
            if (Boolean.FALSE.equals(redis.opsForSet().isMember("ro:wr:active", Long.toString(runId)))) {
                return;
            }
            if (!Boolean.TRUE.equals(redis.hasKey(admitted(runId, userId)))) {
                meters.counter("waiting_room.blocked").increment();
                throw new DomainException(ErrorCode.RES_WAITING_ROOM, "join the waiting room for this train first");
            }
        } catch (DataAccessException e) {
            if (quota.isTatkal()) {
                throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                        "booking is briefly unavailable; your place is not affected, please retry");
            }
            log.warn("waiting room check skipped for run {} (Redis unavailable, non-Tatkal quota)", runId);
        }
    }

    /** Every second on every instance; a per-room leader lock makes exactly one instance admit per tick. */
    @Scheduled(fixedDelay = 1000)
    public void admitTick() {
        Set<String> rooms;
        try {
            rooms = redis.opsForSet().members("ro:wr:active");
        } catch (DataAccessException e) {
            return;
        }
        if (rooms == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String r : rooms) {
            long runId = Long.parseLong(r);
            Map<Object, Object> c = redis.opsForHash().entries(cfg(runId));
            if (c.isEmpty() || now < Long.parseLong((String) c.get("opensAt"))) {
                continue;
            }
            Boolean leader = redis.opsForValue().setIfAbsent("ro:wr:{" + runId + "}:tick", instanceId, Duration.ofMillis(900));
            if (!Boolean.TRUE.equals(leader)) {
                continue;
            }
            Long admitted = redis.execute(ADMIT, List.of(queue(runId)), (String) c.get("rate"),
                    "ro:wr:{" + runId + "}:adm:", Long.toString(ADMISSION_TTL.toSeconds()));
            if (admitted != null && admitted > 0) {
                meters.counter("waiting_room.admitted").increment(admitted);
            }
        }
    }

    private static String cfg(long runId) {
        return "ro:wr:{" + runId + "}:cfg";
    }

    private static String queue(long runId) {
        return "ro:wr:{" + runId + "}:q";
    }

    private static String admitted(long runId, UUID userId) {
        return "ro:wr:{" + runId + "}:adm:" + userId;
    }
}
