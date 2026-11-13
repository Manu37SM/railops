package in.railops.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import in.railops.common.security.ServiceTokenClient;
import in.railops.testsupport.HttpContract;
import in.railops.testsupport.ItPostgres;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Delivery semantics of the notification listener on real PostgreSQL. Kafka is not needed: the listener method is
 * called directly with a record, which is exactly what the container would do.
 */
@SpringBootTest
@ActiveProfiles("local")
class NotificationDeliveryIT {

    static final ItPostgres.Db DB = ItPostgres.newDatabase();
    static final HttpServer RESERVATION = HttpContract.load("notification-to-reservation.contact")
            .stubProvider(new HttpContract.Recorded());

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::url);
        r.add("spring.datasource.username", DB::user);
        r.add("spring.datasource.password", DB::password);
        r.add("spring.kafka.listener.auto-startup", () -> "false");
        r.add("spring.kafka.admin.auto-create", () -> "false");
        r.add("spring.kafka.bootstrap-servers", () -> "127.0.0.1:1");
        r.add("railops.reservation.base-url", () -> "http://127.0.0.1:" + RESERVATION.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        RESERVATION.stop(0);
    }

    @MockitoBean ServiceTokenClient tokens;
    @MockitoSpyBean NotificationApplication.SmsSender sms;
    @Autowired NotificationApplication.Notifier notifier;
    @Autowired JdbcClient jdbc;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE notification.delivery").update();
        when(tokens.token()).thenReturn("svc-token");
    }

    ConsumerRecord<String, String> event(UUID id) throws Exception {
        String env = json.writeValueAsString(Map.of("eventId", id.toString(), "eventType", "BookingConfirmed",
                "schemaVersion", 1, "occurredAt", "2026-11-12T10:00:00Z", "aggregateId", "2400000018",
                "payload", Map.of("pnr", "2400000018")));
        return new ConsumerRecord<>("reservation.booking.v1", 0, 0L, "2400000018", env);
    }

    String status(UUID id) {
        return jdbc.sql("SELECT status FROM notification.delivery WHERE source_event_id = :e").param("e", id)
                .query(String.class).single();
    }

    @Test
    void the_claim_is_committed_and_no_transaction_is_open_while_the_provider_is_called() throws Exception {
        UUID id = UUID.randomUUID();
        var seenFromOutside = new AtomicReference<String>();
        var txActive = new AtomicReference<Boolean>();
        doAnswer(i -> {
            txActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            try (var c = DriverManager.getConnection(DB.url(), DB.user(), DB.password());
                 var st = c.prepareStatement("SELECT status FROM notification.delivery WHERE source_event_id = ?")) {
                st.setObject(1, id);
                var rs = st.executeQuery();
                seenFromOutside.set(rs.next() ? rs.getString(1) : null);
            }
            return null;
        }).when(sms).send(anyString(), anyString());

        notifier.on(event(id));

        assertThat(txActive.get()).as("no DB transaction held across the provider call").isFalse();
        assertThat(seenFromOutside.get()).as("claim already COMMITTED when the SMS goes out").isEqualTo("SENDING");
        assertThat(status(id)).isEqualTo("SENT");
        verify(sms).send(eq("9820012345"), contains("2400000018"));
    }

    @Test
    void a_provider_failure_is_recorded_and_never_resent_on_redelivery() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new IllegalStateException("provider 503")).when(sms).send(anyString(), anyString());

        notifier.on(event(id));          // does not throw: no Kafka retry storm
        notifier.on(event(id));          // redelivery

        verify(sms, times(1)).send(anyString(), anyString());
        assertThat(status(id)).isEqualTo("FAILED");
    }

    @Test
    void a_claim_left_by_a_crash_before_sending_is_not_resent() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO notification.delivery (source_event_id, pnr, channel, template, recipient_masked, body, status)
                VALUES (:e, '2400000018', 'SMS', 'BookingConfirmed', '98******45', 'x', 'SENDING')""")
                .param("e", id).update();

        notifier.on(event(id));

        verify(sms, never()).send(anyString(), anyString());
        assertThat(status(id)).isEqualTo("SENDING");
    }
}
