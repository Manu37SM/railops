package in.railops.common.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Appends events to the service's outbox table inside the caller's transaction.
 *
 * <p>{@code MANDATORY} propagation is deliberate: writing an outbox row outside the business
 * transaction would reintroduce the dual-write problem the outbox exists to remove.
 */
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final String table;

    public OutboxWriter(JdbcClient jdbc, ObjectMapper mapper, String schema) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        if (!schema.matches("[a-z_]+")) {
            throw new IllegalArgumentException("invalid schema name");
        }
        this.table = schema + ".outbox";
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String topic, String aggregateType, String aggregateId,
                       String eventType, Object payload, Map<String, String> headers) {
        UUID eventId = UUID.randomUUID();
        try {
            jdbc.sql("INSERT INTO " + table + " (event_id, aggregate_type, aggregate_id, topic, event_type, payload, headers) "
                            + "VALUES (:eventId, :aggType, :aggId, :topic, :type, CAST(:payload AS jsonb), CAST(:headers AS jsonb))")
                    .param("eventId", eventId)
                    .param("aggType", aggregateType)
                    .param("aggId", aggregateId)
                    .param("topic", topic)
                    .param("type", eventType)
                    .param("payload", mapper.writeValueAsString(payload))
                    .param("headers", mapper.writeValueAsString(headers == null ? Map.of() : headers))
                    .update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("event payload not serializable: " + eventType, e);
        }
        return eventId;
    }
}
