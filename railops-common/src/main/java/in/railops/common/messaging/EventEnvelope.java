package in.railops.common.messaging;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire format for every Kafka event in RailOps.
 *
 * <p>Consumers dedupe on {@code eventId}. {@code schemaVersion} only changes on a breaking change,
 * which also gets a new topic suffix (.v2); additive fields keep the version and consumers must
 * ignore unknown fields (tolerant reader).
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String aggregateId,
        JsonNode payload) {
}
