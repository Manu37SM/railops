package in.railops.identity.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.railops.common.messaging.EventEnvelope;
import in.railops.identity.service.AuditService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Chains staff-action audit events emitted by other services (via their outboxes) into the single
 * tamper-evident log. Single consumer group, keyed per actor; dedupes on the envelope's event id.
 */
@Component
public class AuditEventsListener {

    private final AuditService audit;
    private final ObjectMapper mapper;

    public AuditEventsListener(AuditService audit, ObjectMapper mapper) {
        this.audit = audit;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "audit.events.v1", groupId = "identity-audit",
            autoStartup = "${railops.audit.consume:true}")
    public void on(ConsumerRecord<String, String> record) throws JsonProcessingException {
        EventEnvelope env = mapper.readValue(record.value(), EventEnvelope.class);
        Map<String, Object> p = mapper.convertValue(env.payload(), new TypeReference<>() {
        });
        audit.recordRemote(env.eventId(), String.valueOf(p.get("actorId")), String.valueOf(p.get("action")),
                String.valueOf(p.get("targetType")), String.valueOf(p.get("targetId")),
                Map.of("source", env.eventType()), Instant.parse(String.valueOf(p.get("occurredAt"))));
    }
}
