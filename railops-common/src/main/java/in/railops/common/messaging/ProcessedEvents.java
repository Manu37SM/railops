package in.railops.common.messaging;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Consumer-side dedupe. Call first inside the handling transaction: if it returns false the
 * event was already applied and the handler must return without side effects.
 *
 * <p>Uses ON CONFLICT DO NOTHING rather than catching a unique violation, because in PostgreSQL
 * any error aborts the surrounding transaction.
 */
public class ProcessedEvents {

    private final JdbcClient jdbc;
    private final String table;

    public ProcessedEvents(JdbcClient jdbc, String schema) {
        if (!schema.matches("[a-z_]+")) {
            throw new IllegalArgumentException("invalid schema name");
        }
        this.jdbc = jdbc;
        this.table = schema + ".processed_event";
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(String consumer, UUID eventId) {
        return jdbc.sql("INSERT INTO " + table + " (consumer, event_id) VALUES (:c, :e) ON CONFLICT DO NOTHING")
                .param("c", consumer)
                .param("e", eventId)
                .update() == 1;
    }
}
