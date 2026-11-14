package in.railops.notification;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import in.railops.common.messaging.EventEnvelope;
import in.railops.common.messaging.Topics;
import in.railops.common.security.ServiceTokenClient;
import in.railops.common.web.PiiMasker;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Turns booking and payment facts into passenger messages.
 *
 * <ul>
 *   <li>At-most-once per (event, channel). A UNIQUE claim row (SENDING) is COMMITTED before the provider call, then
 *       marked SENT or FAILED. A redelivered event finds the claim and sends nothing. If the process dies between
 *       claim and send, that message is lost rather than duplicated (visible as a stuck SENDING row). For SMS
 *       "your WL is now CNF", at-most-once beats spamming on retries.
 *       <br>Phase 15 fix: the listener used to be @Transactional and sent INSIDE the transaction (insert, send,
 *       commit), so a failed commit or a crash after sending caused a resend on redelivery -- the opposite of the
 *       stated guarantee -- and it held a DB connection across the 2.5 s contact lookup and the provider call.</li>
 *   <li>Contact details are pulled per message from reservation (service token, SCOPE_contacts:read), never read
 *       from event payloads.</li>
 *   <li>Rate limit: at most {@value Notifier#MAX_PER_HOUR} messages per PNR per hour; a chart run can emit several
 *       events for one PNR within seconds.</li>
 * </ul>
 */
@SpringBootApplication
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }

    @Bean
    ServiceTokenClient serviceTokenClient(RestClient.Builder b, @Value("${railops.security.token-uri}") String uri,
                                          @Value("${railops.security.client-id}") String id,
                                          @Value("${railops.security.client-secret}") String secret) {
        return new ServiceTokenClient(b, uri, id, secret, "contacts:read");
    }

    @Bean
    NewTopic bookingDltForNotifications(@Value("${railops.kafka.replicas:3}") int r) {
        return Topics.dlt(Topics.BOOKING + ".notification", 12, r);
    }

    @Bean
    NewTopic paymentDltForNotifications(@Value("${railops.kafka.replicas:3}") int r) {
        return Topics.dlt(Topics.PAYMENT + ".notification", 12, r);
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template, MeterRegistry meters) {
        // Notification keeps its longer retry window: the contact lookup depends on reservation being up.
        return in.railops.common.messaging.DeadLetters.errorHandler(template, meters, "notification",
                Duration.ofSeconds(1), Duration.ofSeconds(30));
    }

    /**
     * Claims older than 5 minutes still in SENDING = the process died between claim and provider call: those
     * passengers got no SMS (at-most-once by design). Measured on scrape via the partial index ix_delivery_sending.
     */
    @Bean
    io.micrometer.core.instrument.Gauge stuckSendingGauge(MeterRegistry meters, JdbcClient jdbc) {
        return io.micrometer.core.instrument.Gauge.builder("notification.stuck_sending", jdbc, j -> {
            try {
                return j.sql("SELECT count(*) FROM notification.delivery WHERE status = 'SENDING' "
                        + "AND created_at < now() - interval '5 minutes'").query(Long.class).single();
            } catch (RuntimeException e) {
                return Double.NaN;   // a scrape must never fail because the DB is briefly unavailable
            }
        }).register(meters);
    }

    /** Stand-in for an SMS provider: logs a MASKED recipient. A real adapter would call the provider's API. */
    @Component
    static class SmsSender {
        private static final Logger log = LoggerFactory.getLogger("sms");

        void send(String phone, String body) {
            log.info("SMS to {}: {}", PiiMasker.mask(phone), body);
        }
    }

    @Component
    static class Notifier {

        private static final Logger log = LoggerFactory.getLogger(Notifier.class);
        static final int MAX_PER_HOUR = 6;

        /**
         * Event type -> message. Templates see only non-PII payload fields. Every field is read with {@link #req}:
         * a missing field throws (IllegalArgumentException -> straight to the DLT) instead of texting a customer
         * "Refund Rs  initiated." Pinned by NotificationContractTest against contracts/events.
         */
        static final Map<String, Function<JsonNode, String>> TEMPLATES = Map.of(
                "BookingConfirmed", p -> "RailOps: PNR " + req(p, "pnr") + " is booked. Check your berths in the app.",
                "PassengerPromoted", p -> "RailOps: PNR " + req(p, "pnr") + " passenger is now " + req(p, "statusText") + ".",
                "BookingCancelled", p -> "RailOps: PNR " + req(p, "pnr") + " cancelled. Refund Rs " + req(p, "refundAmount") + " initiated.",
                "WaitlistChartCancelled", p -> "RailOps: PNR " + req(p, "pnr") + " waitlisted passengers not confirmed at chart. Refund Rs " + req(p, "refundAmount") + " initiated.",
                "BookingRunCancelled", p -> "RailOps: your train for PNR " + req(p, "pnr") + " is cancelled. Full refund Rs " + req(p, "refundAmount") + " initiated.",
                "RefundSucceeded", p -> "RailOps: refund of Rs " + req(p, "amount") + " for PNR " + req(p, "pnr") + " processed.");

        static String req(JsonNode payload, String field) {
            JsonNode v = payload.get(field);
            if (v == null || v.isNull() || v.isContainer() || v.asString().isBlank()) {
                throw new IllegalArgumentException("payload field '" + field + "' missing or empty");
            }
            return v.asString();
        }

        private final JdbcClient jdbc;
        private final ObjectMapper mapper;
        private final RestClient reservation;
        private final ServiceTokenClient tokens;
        private final SmsSender sms;
        private final MeterRegistry meters;

        Notifier(JdbcClient jdbc, ObjectMapper mapper, RestClient.Builder builder, ServiceTokenClient tokens, SmsSender sms,
                 MeterRegistry meters, @Value("${railops.reservation.base-url}") String reservationUrl) {
            var rf = new SimpleClientHttpRequestFactory();
            rf.setConnectTimeout(Duration.ofMillis(500));
            rf.setReadTimeout(Duration.ofSeconds(2));
            this.reservation = builder.baseUrl(reservationUrl).requestFactory(rf).build();
            this.jdbc = jdbc;
            this.mapper = mapper;
            this.tokens = tokens;
            this.sms = sms;
            this.meters = meters;
        }

        @KafkaListener(topics = {Topics.BOOKING, Topics.PAYMENT}, groupId = "notification")
        public void on(ConsumerRecord<String, String> rec) throws Exception {
            EventEnvelope env = mapper.readValue(rec.value(), EventEnvelope.class);
            Function<JsonNode, String> template = TEMPLATES.get(env.eventType());
            if (template == null) {
                return;
            }
            String pnr = env.payload().path("pnr").asString();
            if (!pnr.matches("[1-9]\\d{9}")) {
                throw new IllegalArgumentException("event " + env.eventId() + " has no valid PNR"); // straight to DLT
            }
            String body = template.apply(env.payload());
            // No transaction anywhere in this method: each statement auto-commits, and no connection is held while
            // we wait on reservation (contact) or the SMS provider.
            if (claimed(env.eventId())) {
                meters.counter("notification.duplicate").increment();
                return; // redelivery: skip before paying for a contact lookup
            }
            Integer recent = jdbc.sql("""
                            SELECT count(*) FROM notification.delivery
                            WHERE pnr = :pnr AND status IN ('SENDING','SENT') AND created_at > now() - interval '1 hour'
                            """).param("pnr", pnr).query(Integer.class).single();
            Optional<String> phone = recent >= MAX_PER_HOUR ? Optional.empty() : contactPhone(pnr);
            String status = recent >= MAX_PER_HOUR ? "SUPPRESSED_RATE_LIMIT" : phone.isPresent() ? "SENDING" : "NO_CONTACT";
            int inserted = jdbc.sql("""
                            INSERT INTO notification.delivery (source_event_id, pnr, channel, template, recipient_masked, body, status)
                            VALUES (:e, :pnr, 'SMS', :t, :r, :b, :s) ON CONFLICT (source_event_id, channel) DO NOTHING
                            """)
                    .param("e", env.eventId()).param("pnr", pnr).param("t", env.eventType())
                    .param("r", phone.map(PiiMasker::mask).orElse("-")).param("b", body).param("s", status)
                    .update();
            if (inserted == 0) {
                meters.counter("notification.duplicate").increment();
                return; // a concurrent redelivery claimed it first
            }
            if ("SENDING".equals(status)) {
                status = deliver(env.eventId(), phone.get(), body);
            }
            meters.counter("notification.processed", "status", status, "template", env.eventType()).increment();
        }

        /** The claim is committed; whatever happens now, this event is never sent again. */
        private String deliver(java.util.UUID eventId, String phone, String body) {
            String outcome;
            try {
                sms.send(phone, body);
                outcome = "SENT";
            } catch (RuntimeException e) {
                // Not rethrown: a Kafka retry would find the claim and skip anyway; the failure is recorded instead.
                log.warn("SMS for event {} failed: {}", eventId, e.toString());
                outcome = "FAILED";
            }
            jdbc.sql("""
                            UPDATE notification.delivery SET status = :s, updated_at = now()
                            WHERE source_event_id = :e AND channel = 'SMS' AND status = 'SENDING'
                            """).param("s", outcome).param("e", eventId).update();
            return outcome;
        }

        private boolean claimed(java.util.UUID eventId) {
            return jdbc.sql("SELECT EXISTS (SELECT 1 FROM notification.delivery WHERE source_event_id = :e AND channel = 'SMS')")
                    .param("e", eventId).query(Boolean.class).single();
        }

        Optional<String> contactPhone(String pnr) {   // package-private: HTTP contract test
            try {
                JsonNode c = reservation.get().uri("/internal/v1/bookings/{pnr}/contact", pnr)
                        .header("Authorization", "Bearer " + tokens.token())
                        .accept(org.springframework.http.MediaType.APPLICATION_JSON)
                        .retrieve().body(JsonNode.class);
                return Optional.ofNullable(c).map(n -> n.path("phone").asString(null));
            } catch (HttpClientErrorException.NotFound e) {
                return Optional.empty();
            } catch (HttpClientErrorException.Unauthorized e) {
                tokens.invalidate();
                throw e; // retried by the error handler with a fresh token
            }
        }
    }
}
