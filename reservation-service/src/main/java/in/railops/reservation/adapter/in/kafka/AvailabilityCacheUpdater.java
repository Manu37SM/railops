package in.railops.reservation.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.railops.common.messaging.EventEnvelope;
import in.railops.common.messaging.Topics;
import in.railops.reservation.application.cache.AvailabilityCache;
import in.railops.reservation.contracts.ReservationEvents.InventoryChanged;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the Redis availability read model fresh. Its OWN consumer group, separate from promotion: a slow
 * promotion (it takes DB locks) must never delay cache freshness, and a cache outage must never delay promotion.
 * Full-pool recompute per event, so redelivery and reordering are harmless.
 */
@Component
public class AvailabilityCacheUpdater {

    private final AvailabilityCache cache;
    private final ObjectMapper mapper;

    public AvailabilityCacheUpdater(AvailabilityCache cache, ObjectMapper mapper) {
        this.cache = cache;
        this.mapper = mapper;
    }

    @KafkaListener(topics = Topics.INVENTORY, groupId = "reservation-avail-cache")
    public void on(ConsumerRecord<String, String> record) throws Exception {
        EventEnvelope env = mapper.readValue(record.value(), EventEnvelope.class);
        if (!"InventoryChanged".equals(env.eventType())) {
            return;
        }
        InventoryChanged e = mapper.treeToValue(env.payload(), InventoryChanged.class);
        if ("RUN_STATUS".equals(e.reason())) {
            try {
                cache.refreshStatus(e.trainRunId());
            } catch (AvailabilityCache.CacheUnavailable ex) {
                // Redis down: the 6 h key TTL is the repair path; booking itself re-checks status under locks.
            }
            return;
        }
        try {
            cache.refreshPool(e.trainRunId(), e.classCode(), e.quotaCode());
        } catch (AvailabilityCache.CacheUnavailable ex) {
            // Redis down: skip. Pool keys repair themselves on the next event or on a miss after TTL.
        }
    }
}
