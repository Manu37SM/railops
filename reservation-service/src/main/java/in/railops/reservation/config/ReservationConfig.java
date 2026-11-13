package in.railops.reservation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.railops.common.messaging.OutboxWriter;
import in.railops.common.messaging.ProcessedEvents;
import in.railops.common.web.CorrelationIdFilter;
import in.railops.reservation.domain.policy.PnrGenerator;
import in.railops.reservation.domain.policy.QuotaEligibilityPolicy;
import in.railops.reservation.security.ContactProtector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;

import java.security.SecureRandom;

@Configuration
public class ReservationConfig {

    @Bean
    OutboxWriter outboxWriter(JdbcClient jdbc, ObjectMapper mapper) {
        return new OutboxWriter(jdbc, mapper, "reservation");
    }

    @Bean
    ProcessedEvents processedEvents(JdbcClient jdbc) {
        return new ProcessedEvents(jdbc, "reservation");
    }

    @Bean
    ContactProtector contactProtector(@Value("${railops.crypto.contact-key-id:1}") int keyId,
                                      @Value("${railops.crypto.contact-enc-key}") String encKey,
                                      @Value("${railops.crypto.contact-hmac-key}") String macKey) {
        return new ContactProtector(new in.railops.common.crypto.FieldCipher(keyId, encKey), macKey);
    }

    @Bean
    QuotaEligibilityPolicy quotaEligibilityPolicy(
            @Value("${railops.quota.senior-male-min-age:60}") int seniorMale,
            @Value("${railops.quota.senior-female-min-age:58}") int seniorFemale,
            @Value("${railops.quota.ladies-max-child-age:11}") int ladiesChild) {
        return new QuotaEligibilityPolicy(seniorMale, seniorFemale, ladiesChild);
    }

    /** Illustrative tariff values (see RefundPolicy); production values come from configuration. */
    @Bean
    in.railops.reservation.domain.policy.RefundPolicy refundPolicy(
            @Value("${railops.refund.clerkage:60.00}") java.math.BigDecimal clerkage,
            @Value("#{${railops.refund.flat-by-class:{'1A':240.00,'2A':200.00,'3A':180.00,'3E':180.00,'CC':180.00,'EC':240.00,'SL':120.00,'2S':60.00}}}")
            java.util.Map<String, java.math.BigDecimal> flat) {
        return new in.railops.reservation.domain.policy.RefundPolicy(
                new in.railops.reservation.domain.policy.RefundPolicy.Config(flat, clerkage));
    }

    @Bean
    io.swagger.v3.oas.models.OpenAPI openApi() {
        return new io.swagger.v3.oas.models.OpenAPI()
                .info(new io.swagger.v3.oas.models.info.Info().title("RailOps Reservation API").version("v1")
                        .description("Booking, payment intent, cancellation, availability and staff operations. "
                                + "Errors are RFC 9457 problem details with a stable `code`. Mutations that move money "
                                + "or inventory require an `Idempotency-Key` header."))
                .components(new io.swagger.v3.oas.models.Components().addSecuritySchemes("bearer",
                        new io.swagger.v3.oas.models.security.SecurityScheme()
                                .type(io.swagger.v3.oas.models.security.SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT")));
    }

    @Bean
    PnrGenerator pnrGenerator() {
        return new PnrGenerator(new SecureRandom());
    }

    // ---- topics owned by reservation-service (and DLTs for the topics it consumes)
    @Bean
    org.apache.kafka.clients.admin.NewTopic bookingTopic(@Value("${railops.kafka.replicas:3}") int r) {
        return in.railops.common.messaging.Topics.topic(in.railops.common.messaging.Topics.BOOKING, 12, r, java.time.Duration.ofDays(7));
    }

    @Bean
    org.apache.kafka.clients.admin.NewTopic inventoryTopic(@Value("${railops.kafka.replicas:3}") int r) {
        return in.railops.common.messaging.Topics.topic(in.railops.common.messaging.Topics.INVENTORY, 12, r, java.time.Duration.ofDays(2));
    }

    @Bean
    org.apache.kafka.clients.admin.NewTopic disruptionTopic(@Value("${railops.kafka.replicas:3}") int r) {
        return in.railops.common.messaging.Topics.topic(in.railops.common.messaging.Topics.DISRUPTION, 6, r, java.time.Duration.ofDays(30));
    }

    @Bean
    org.apache.kafka.clients.admin.NewTopic auditTopic(@Value("${railops.kafka.replicas:3}") int r) {
        return in.railops.common.messaging.Topics.topic(in.railops.common.messaging.Topics.AUDIT, 6, r, java.time.Duration.ofDays(30));
    }

    @Bean
    org.apache.kafka.clients.admin.NewTopic paymentDlt(@Value("${railops.kafka.replicas:3}") int r) {
        return in.railops.common.messaging.Topics.dlt(in.railops.common.messaging.Topics.PAYMENT + ".reservation", 12, r);
    }

    @Bean
    org.apache.kafka.clients.admin.NewTopic inventoryDlt(@Value("${railops.kafka.replicas:3}") int r) {
        return in.railops.common.messaging.Topics.dlt(in.railops.common.messaging.Topics.INVENTORY + ".reservation", 12, r);
    }

    @Bean
    org.apache.kafka.clients.admin.NewTopic disruptionDlt(@Value("${railops.kafka.replicas:3}") int r) {
        return in.railops.common.messaging.Topics.dlt(in.railops.common.messaging.Topics.DISRUPTION + ".reservation", 6, r);
    }

    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> reg = new FilterRegistrationBean<>(new CorrelationIdFilter());
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }

    /**
     * Consumer failures: 4 retries with exponential backoff, then dead-letter to {topic}.dlt on the same
     * partition (keeps per-key grouping visible to ops tooling). Parse errors skip retries entirely:
     * a malformed message will never parse on retry.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template,
                                          io.micrometer.core.instrument.MeterRegistry meters) {
        // Consumer-scoped DLT and the dead-letter counter (INC-003) live in railops-common now.
        return in.railops.common.messaging.DeadLetters.errorHandler(template, meters, "reservation");
    }
}
