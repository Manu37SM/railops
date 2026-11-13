package in.railops.reservation.adapter.in.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.railops.common.messaging.EventEnvelope;
import in.railops.reservation.application.PromotionService;
import in.railops.reservation.contracts.ReservationEvents;
import in.railops.reservation.contracts.ReservationEvents.InventoryChanged;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Triggers WL promotion when capacity is freed. Consumes reservation's own topic so promotion runs
 * asynchronously from cancellation and is partitioned by run (key = trainRunId). No processed_event
 * dedupe needed: promotion is naturally idempotent (a re-run finds nothing new to promote).
 */
@Component
public class InventoryEventsListener {

    private final PromotionService promotions;
    private final ObjectMapper mapper;

    public InventoryEventsListener(PromotionService promotions, ObjectMapper mapper) {
        this.promotions = promotions;
        this.mapper = mapper;
    }

    @KafkaListener(topics = ReservationEvents.TOPIC_INVENTORY, groupId = "reservation-promotion")
    public void on(ConsumerRecord<String, String> record) throws JsonProcessingException {
        EventEnvelope env = mapper.readValue(record.value(), EventEnvelope.class);
        if (!"InventoryChanged".equals(env.eventType())) {
            return;
        }
        InventoryChanged e = mapper.treeToValue(env.payload(), InventoryChanged.class);
        if (e.freesCapacity()) {
            promotions.promoteAll(e.trainRunId(), e.classCode(), e.quotaCode());
        }
    }
}
