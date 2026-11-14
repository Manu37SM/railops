package in.railops.payment;

import tools.jackson.databind.ObjectMapper;
import in.railops.common.messaging.EventEnvelope;
import in.railops.common.messaging.OutboxRelay;
import in.railops.common.messaging.OutboxWriter;
import in.railops.common.messaging.ProcessedEvents;
import in.railops.common.messaging.Topics;
import in.railops.common.security.RailopsClaims;
import in.railops.common.security.RailopsJwtAuthenticationConverter;
import in.railops.common.security.RailopsJwtValidators;
import in.railops.payment.gateway.WebhookSignature;
import in.railops.payment.service.Reconciler;
import in.railops.payment.service.RefundService;
import in.railops.payment.service.RefundService.RefundRequested;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Set;

@SpringBootApplication
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }

    // ------------------------------------------------------------------ security

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        // Authenticated by HMAC signature in the controller, not by a token.
                        .requestMatchers(HttpMethod.POST, "/webhooks/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/sim-gateway/**").permitAll() // bean exists only in local profile
                        .requestMatchers(HttpMethod.GET, "/internal/v1/payments/status")
                        .access(AuthorityAuthorizationManager.hasAuthority("SCOPE_payments:read"))
                        .requestMatchers(HttpMethod.POST, "/internal/v1/payments")
                        .access(AuthorityAuthorizationManager.hasAuthority("SCOPE_payments:create"))
                        // Phase 5, sim profile only (the controller doesn't exist otherwise): reservation on the passenger's behalf.
                        .requestMatchers(HttpMethod.POST, "/internal/v1/sim/payments/*/pay")
                        .access(AuthorityAuthorizationManager.hasAuthority("SCOPE_payments:create"))
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(new RailopsJwtAuthenticationConverter())));
        return http.build();
    }

    /** Only service tokens: a passenger's token (audience railops-api) is rejected before any scope check. */
    @Bean
    JwtDecoder jwtDecoder(@Value("${railops.security.jwk-set-uri}") String jwks, @Value("${railops.security.issuer}") String issuer) {
        NimbusJwtDecoder d = NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        d.setJwtValidator(RailopsJwtValidators.forAudiences(issuer, Set.of(RailopsClaims.AUDIENCE_INTERNAL)));
        return d;
    }

    @Bean
    WebhookSignature webhookSignature(@Value("${railops.gateway.webhook-secret}") String secret) {
        return new WebhookSignature(secret);
    }

    // ------------------------------------------------------------------ messaging

    @Bean
    OutboxWriter outboxWriter(JdbcClient jdbc, ObjectMapper mapper) {
        return new OutboxWriter(jdbc, mapper, "payment");
    }

    @Bean
    ProcessedEvents processedEvents(JdbcClient jdbc) {
        return new ProcessedEvents(jdbc, "payment");
    }

    @Bean
    OutboxRelay outboxRelay(JdbcClient jdbc, TransactionTemplate tx, KafkaTemplate<String, String> kafka,
                            ObjectMapper mapper, MeterRegistry meters) {
        return new OutboxRelay(jdbc, tx, kafka, mapper, meters, "payment", 500);
    }

    /** payment-service owns payment.events.v1 and the DLT of the topic it consumes. */
    @Bean
    NewTopic paymentEvents(@Value("${railops.kafka.replicas:3}") int replicas) {
        return Topics.topic(Topics.PAYMENT, 12, replicas, Duration.ofDays(7));
    }

    @Bean
    NewTopic bookingDltForPayments(@Value("${railops.kafka.replicas:3}") int replicas) {
        return Topics.dlt(Topics.BOOKING + ".payment", 12, replicas);
    }

    /**
     * Retries 4x with backoff, then dead-letters. The DLT name includes the consumer ("...payment.dlt") because
     * several services consume reservation.booking.v1 and each must own its own failures.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template,
                                          io.micrometer.core.instrument.MeterRegistry meters) {
        return in.railops.common.messaging.DeadLetters.errorHandler(template, meters, "payment");
    }

    @Component
    static class Jobs {

        private final OutboxRelay relay;
        private final RefundService refunds;
        private final Reconciler reconciler;

        Jobs(OutboxRelay relay, RefundService refunds, Reconciler reconciler) {
            this.relay = relay;
            this.refunds = refunds;
            this.reconciler = reconciler;
        }

        @Scheduled(fixedDelayString = "${railops.outbox.poll-ms:100}")
        void relay() {
            relay.poll();
        }

        @Scheduled(fixedDelayString = "${railops.refund.poll-ms:2000}")
        void refunds() {
            refunds.submitDue(20);
        }

        @Scheduled(fixedDelayString = "${railops.reconcile.poll-ms:60000}")
        void reconcile() {
            reconciler.run();
        }
    }

    @Component
    static class BookingEventsListener {

        private final RefundService refunds;
        private final ObjectMapper mapper;

        BookingEventsListener(RefundService refunds, ObjectMapper mapper) {
            this.refunds = refunds;
            this.mapper = mapper;
        }

        @KafkaListener(topics = Topics.BOOKING, groupId = "payment-refunds")
        void on(ConsumerRecord<String, String> rec) throws Exception {
            EventEnvelope env = mapper.readValue(rec.value(), EventEnvelope.class);
            if ("RefundRequested".equals(env.eventType())) {
                refunds.accept(env.eventId(), mapper.treeToValue(env.payload(), RefundRequested.class));
            }
        }
    }
}
