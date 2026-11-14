package in.railops.common.messaging;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Polling outbox relay.
 *
 * <p><b>Single active relay.</b> With several instances each grabbing rows via SKIP LOCKED, instance B
 * could publish event 2 of a PNR while instance A still holds event 1: per-key order breaks. So each
 * poll first takes a transaction-scoped advisory lock; only one instance publishes at a time and the
 * others are hot standbys. Throughput comes from batching instead (up to {@code batch} rows per poll,
 * immediate re-poll when the batch is full). Scale-out path if one relay isn't enough: shard the relay
 * by hash(aggregate_id) with one advisory lock per shard.
 *
 * <p>Within a batch, after the first failed send for a key, later events for that key are held back,
 * so a retry can never overtake an earlier event of the same aggregate.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private record Row(long id, UUID eventId, String aggregateId, String topic, String eventType,
                       String payload, String headers, OffsetDateTime createdAt) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final int batch;
    private final String table;
    private final long relayLockKey;
    private final AtomicLong backlog = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();
    private volatile long statsRefreshedAt;

    /**
     * @param schema owning service's schema; the outbox table is {schema}.outbox and the leader lock key is
     *               derived from it, so two services sharing a database cluster don't block each other's relay.
     */
    public OutboxRelay(JdbcClient jdbc, TransactionTemplate tx, KafkaTemplate<String, String> kafka,
                       ObjectMapper mapper, MeterRegistry meters, String schema, int batch) {
        if (!schema.matches("[a-z_]+")) {
            throw new IllegalArgumentException("invalid schema name");
        }
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafka = kafka;
        this.mapper = mapper;
        this.batch = batch;
        this.table = schema + ".outbox";
        this.relayLockKey = 0x524F000000000000L | (schema.hashCode() & 0xFFFFFFFFL);
        var tags = java.util.List.of(io.micrometer.core.instrument.Tag.of("schema", schema));
        // INC-001: the old gauge reported the size of the last FETCHED batch (capped, frozen during backoff).
        // These come from an independent query, refreshed even while publishing is paused. ALERT ON AGE, not count:
        // 16 events stuck for 2 minutes is an outage; 100 events from the last second is a busy minute.
        meters.gauge("outbox.backlog", tags, backlog);
        meters.gauge("outbox.oldest.age.seconds", tags, oldestAgeSeconds);
    }

    /** Exponential backoff while Kafka is unreachable: 100ms polls would otherwise spin on a dead broker. */
    private volatile long pausedUntilMillis;
    private volatile long currentPauseMillis;
    // 5 s, not 30 s: INC-001 drained 25 s after the network healed. Cost during an outage: one probe (bounded by
    // max.block.ms) every 5 s.
    private static final long MAX_PAUSE_MILLIS = 5_000;

    /** Call on a fixed delay (e.g. every 100 ms) from the owning service's scheduler. */
    public void poll() {
        refreshStats();
        if (System.currentTimeMillis() < pausedUntilMillis) {
            return;
        }
        int published;
        do {
            Integer n = tx.execute(status -> publishBatch());
            published = n == null ? 0 : n;
        } while (published == batch);
        if (published < 0) {
            currentPauseMillis = Math.min(MAX_PAUSE_MILLIS, Math.max(500, currentPauseMillis * 2));
            pausedUntilMillis = System.currentTimeMillis() + currentPauseMillis;
        } else {
            currentPauseMillis = 0;
        }
    }

    private void refreshStats() {
        long now = System.currentTimeMillis();
        if (now - statsRefreshedAt < 5_000) {
            return;
        }
        statsRefreshedAt = now;
        try {
            jdbc.sql("SELECT count(*), COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0) FROM " + table
                            + " WHERE published_at IS NULL")
                    .query(rs -> {
                        backlog.set(rs.getLong(1));
                        oldestAgeSeconds.set((long) rs.getDouble(2));
                    });
        } catch (RuntimeException e) {
            log.warn("outbox stats query failed: {}", e.toString());
        }
    }

    private int publishBatch() {
        Boolean leader = jdbc.sql("SELECT pg_try_advisory_xact_lock(:k)").param("k", relayLockKey)
                .query(Boolean.class).single();
        if (!Boolean.TRUE.equals(leader)) {
            return 0;
        }
        List<Row> rows = jdbc.sql("""
                        SELECT id, event_id, aggregate_id, topic, event_type, payload::text AS payload,
                               headers::text AS headers, created_at
                        FROM %s WHERE published_at IS NULL
                        ORDER BY id LIMIT :n
                        """.formatted(table))
                .param("n", batch)
                .query((rs, i) -> new Row(rs.getLong("id"), rs.getObject("event_id", UUID.class),
                        rs.getString("aggregate_id"), rs.getString("topic"), rs.getString("event_type"),
                        rs.getString("payload"), rs.getString("headers"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
        if (rows.isEmpty()) {
            return 0;
        }

        // Probe with the first row before fanning out: if Kafka is down, each send() would block up to
        // max.block.ms waiting for metadata, and 500 of them would pin this DB transaction for minutes.
        try {
            kafka.send(toRecord(rows.getFirst())).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            jdbc.sql("UPDATE " + table + " SET attempts = attempts + 1 WHERE id = :id")
                    .param("id", rows.getFirst().id()).update();
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            if (root instanceof org.apache.kafka.common.config.ConfigException) {
                // Not an outage: no retry will ever succeed. Shout, so it isn't mistaken for a broker problem.
                log.error("outbox relay: producer MISCONFIGURED, events are not being published: {}", root.getMessage());
            } else {
                log.warn("outbox relay: Kafka unavailable, backing off ({}: {})", root.getClass().getSimpleName(), root.getMessage());
            }
            return -1;
        }
        List<CompletableFuture<SendResult<String, String>>> futures = new ArrayList<>(rows.size());
        futures.add(CompletableFuture.completedFuture(null));
        for (Row r : rows.subList(1, rows.size())) {
            futures.add(kafka.send(toRecord(r)));
        }
        List<Long> ok = new ArrayList<>();
        List<Long> failed = new ArrayList<>();
        Set<String> blockedKeys = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            String orderingKey = r.topic() + "|" + r.aggregateId();
            if (blockedKeys.contains(orderingKey)) {
                failed.add(r.id());
                continue;
            }
            try {
                futures.get(i).get(10, TimeUnit.SECONDS);
                ok.add(r.id());
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                blockedKeys.add(orderingKey);
                failed.add(r.id());
                log.warn("outbox publish failed for event {} ({}): {}", r.eventId(), r.eventType(), e.toString());
            }
        }
        if (!ok.isEmpty()) {
            jdbc.sql("UPDATE " + table + " SET published_at = now() WHERE id IN (:ids)").param("ids", ok).update();
        }
        if (!failed.isEmpty()) {
            jdbc.sql("UPDATE " + table + " SET attempts = attempts + 1 WHERE id IN (:ids)").param("ids", failed).update();
        }

        // A failed key means Kafka is unhappy: stop this cycle and back off.
        return failed.isEmpty() ? ok.size() : -1;
    }

    private ProducerRecord<String, String> toRecord(Row r) {
        try {
            JsonNode payload = mapper.readTree(r.payload());
            EventEnvelope env = new EventEnvelope(r.eventId(), r.eventType(), 1, r.createdAt().toInstant(),
                    r.aggregateId(), payload);
            ProducerRecord<String, String> rec = new ProducerRecord<>(r.topic(), r.aggregateId(),
                    mapper.writeValueAsString(env));
            rec.headers().add("eventId", r.eventId().toString().getBytes(StandardCharsets.UTF_8));
            rec.headers().add("eventType", r.eventType().getBytes(StandardCharsets.UTF_8));
            Map<String, String> extra = mapper.readValue(r.headers(), new TypeReference<>() {
            });
            extra.forEach((k, v) -> rec.headers().add(k, v.getBytes(StandardCharsets.UTF_8)));
            return rec;
        } catch (JacksonException e) {
            throw new IllegalStateException("corrupt outbox row " + r.id(), e);
        }
    }
}
