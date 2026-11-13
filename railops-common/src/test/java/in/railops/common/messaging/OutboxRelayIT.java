package in.railops.common.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariDataSource;
import in.railops.testsupport.ItPostgres;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transactional outbox against a REAL broker and REAL PostgreSQL: every committed event is published, at least
 * once, in order per aggregate, by exactly one relay at a time; a dead broker loses nothing.
 */
class OutboxRelayIT {

    static final String TOPIC = "it.outbox.v1";
    static EmbeddedKafkaKraftBroker kafka;
    static HikariDataSource ds;
    static JdbcClient jdbc;
    static TransactionTemplate tx;
    static final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeAll
    static void infra() {
        kafka = new EmbeddedKafkaKraftBroker(1, 6, TOPIC);
        kafka.afterPropertiesSet();
        ItPostgres.Db db = ItPostgres.newDatabase();
        ds = new HikariDataSource();
        ds.setJdbcUrl(db.url());
        ds.setUsername(db.user());
        ds.setPassword(db.password());
        ds.setMaximumPoolSize(8);
        jdbc = JdbcClient.create(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.sql("""
                CREATE SCHEMA relay;
                CREATE TABLE relay.outbox (
                  id BIGSERIAL PRIMARY KEY, event_id UUID NOT NULL UNIQUE, aggregate_type TEXT NOT NULL,
                  aggregate_id TEXT NOT NULL, topic TEXT NOT NULL, event_type TEXT NOT NULL, payload JSONB NOT NULL,
                  headers JSONB NOT NULL DEFAULT '{}', created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  published_at TIMESTAMPTZ, attempts INT NOT NULL DEFAULT 0);
                CREATE TABLE relay.processed_event (consumer TEXT NOT NULL, event_id UUID NOT NULL,
                  processed_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY (consumer, event_id));
                """).update();
    }

    @AfterAll
    static void down() {
        ds.close();
        kafka.destroy();
    }

    @BeforeEach
    void empty() {
        jdbc.sql("TRUNCATE relay.outbox, relay.processed_event").update();
    }

    static KafkaTemplate<String, String> template(String bootstrap) {
        Map<String, Object> p = new HashMap<>();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000);
        p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3000);    // must be >= linger + request timeout (Phase 9 lesson)
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(p));
    }

    static OutboxRelay relay(KafkaTemplate<String, String> t) {
        return new OutboxRelay(jdbc, tx, t, mapper, new SimpleMeterRegistry(), "relay", 50);
    }

    /** Appends n events per aggregate, each carrying its sequence number, in one transaction per event. */
    static int append(int aggregates, int perAggregate) {
        OutboxWriter writer = new OutboxWriter(jdbc, mapper, "relay");
        for (int s = 0; s < perAggregate; s++) {
            for (int a = 0; a < aggregates; a++) {
                int seq = s, agg = a;
                tx.executeWithoutResult(st -> writer.append(TOPIC, "Booking", "PNR" + agg, "Ping", Map.of("seq", seq), Map.of()));
            }
        }
        return aggregates * perAggregate;
    }

    static List<ConsumerRecord<String, String>> consumeAll(int expectedAtLeast) {
        Map<String, Object> c = new HashMap<>();
        c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBrokersAsString());
        c.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        c.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        c.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> out = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(c)) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.currentTimeMillis() + 20_000;
            int quiet = 0;
            while (System.currentTimeMillis() < deadline && (out.size() < expectedAtLeast || quiet < 3)) {
                var batch = consumer.poll(Duration.ofMillis(500));
                batch.forEach(out::add);
                quiet = batch.isEmpty() ? quiet + 1 : 0;
            }
        }
        return out;
    }

    static int unpublished() {
        return jdbc.sql("SELECT count(*) FROM relay.outbox WHERE published_at IS NULL").query(Integer.class).single();
    }

    @Test
    void every_event_is_published_exactly_once_in_order_per_aggregate() throws Exception {
        // Previous tests' messages are on the topic too: track only this test's event ids.
        int n = append(10, 25);
        Set<String> mine = new HashSet<>(jdbc.sql("SELECT event_id::text FROM relay.outbox").query(String.class).list());
        OutboxRelay relay = relay(template(kafka.getBrokersAsString()));
        while (unpublished() > 0) relay.poll();

        List<ConsumerRecord<String, String>> got = consumeAll(n).stream()
                .filter(r -> mine.contains(header(r, "eventId"))).toList();
        Map<String, Long> perEvent = new HashMap<>();
        got.forEach(r -> perEvent.merge(header(r, "eventId"), 1L, Long::sum));
        assertThat(perEvent).hasSize(n);
        assertThat(perEvent.values()).allMatch(c -> c == 1);

        Map<String, Integer> lastSeq = new HashMap<>();
        for (ConsumerRecord<String, String> r : got) {   // poll order within a partition = log order
            JsonNode env = mapper.readTree(r.value());
            int seq = env.path("payload").path("seq").asInt();
            Integer prev = lastSeq.put(r.key(), seq);
            if (prev != null) assertThat(seq).as("order for " + r.key()).isGreaterThan(prev);
            assertThat(env.path("eventType").asText()).isEqualTo("Ping");
        }
    }

    @Test
    void two_relays_polling_concurrently_never_publish_the_same_row_twice() throws Exception {
        int n = append(8, 40);
        Set<String> mine = new HashSet<>(jdbc.sql("SELECT event_id::text FROM relay.outbox").query(String.class).list());
        OutboxRelay a = relay(template(kafka.getBrokersAsString()));
        OutboxRelay b = relay(template(kafka.getBrokersAsString()));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Void> loop1 = () -> { while (unpublished() > 0) a.poll(); return null; };
        Callable<Void> loop2 = () -> { while (unpublished() > 0) b.poll(); return null; };
        for (Future<Void> f : pool.invokeAll(List.of(loop1, loop2))) f.get();
        pool.shutdown();

        Map<String, Long> perEvent = new HashMap<>();
        consumeAll(n).stream().filter(r -> mine.contains(header(r, "eventId")))
                .forEach(r -> perEvent.merge(header(r, "eventId"), 1L, Long::sum));
        assertThat(perEvent).hasSize(n);
        assertThat(perEvent.values()).as("advisory-lock leader election").allMatch(c -> c == 1);
    }

    @Test
    void a_dead_broker_loses_nothing_and_publishing_resumes_when_it_is_back() {
        int n = append(2, 3);
        OutboxRelay dead = relay(template("127.0.0.1:1"));
        long t0 = System.currentTimeMillis();
        dead.poll();
        dead.poll();   // inside the backoff window: returns immediately, does not hammer the broker
        assertThat(System.currentTimeMillis() - t0).as("bounded by max.block.ms, not minutes").isLessThan(10_000);
        assertThat(unpublished()).isEqualTo(n);
        assertThat(jdbc.sql("SELECT max(attempts) FROM relay.outbox").query(Integer.class).single()).isEqualTo(1);

        OutboxRelay alive = relay(template(kafka.getBrokersAsString()));
        while (unpublished() > 0) alive.poll();
        assertThat(unpublished()).isZero();
    }

    @Test
    void outbox_writes_require_the_callers_transaction() {
        OutboxWriter writer = new OutboxWriter(jdbc, mapper, "relay");
        // MANDATORY propagation only works through a Spring proxy; here we assert the table-level guarantee that
        // matters: a rolled-back business transaction leaves no event behind.
        try {
            tx.executeWithoutResult(s -> {
                writer.append(TOPIC, "Booking", "PNR1", "Ping", Map.of("seq", 1), Map.of());
                throw new IllegalStateException("business rule failed");
            });
        } catch (IllegalStateException expected) {
            // rolled back
        }
        assertThat(jdbc.sql("SELECT count(*) FROM relay.outbox").query(Integer.class).single()).isZero();
    }

    @Test
    void processed_events_deduplicate_per_consumer() {
        UUID e = UUID.randomUUID();
        ProcessedEvents processed = new ProcessedEvents(jdbc, "relay");
        assertThat((Boolean) tx.execute(s -> processed.markProcessed("payments", e))).isTrue();
        assertThat((Boolean) tx.execute(s -> processed.markProcessed("payments", e))).isFalse();
        assertThat((Boolean) tx.execute(s -> processed.markProcessed("notification", e))).as("other consumer").isTrue();
    }

    static String header(ConsumerRecord<String, String> r, String k) {
        var h = r.headers().lastHeader(k);
        return h == null ? null : new String(h.value());
    }
}
