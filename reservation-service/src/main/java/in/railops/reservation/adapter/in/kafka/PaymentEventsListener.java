package in.railops.reservation.adapter.in.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.railops.common.messaging.EventEnvelope;
import in.railops.reservation.application.PaymentConfirmationService;
import in.railops.reservation.contracts.PaymentEvents;
import in.railops.reservation.contracts.PaymentEvents.PaymentCaptured;
import in.railops.reservation.contracts.PaymentEvents.PaymentFailed;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventsListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventsListener.class);

    private final PaymentConfirmationService confirmations;
    private final ObjectMapper mapper;

    public PaymentEventsListener(PaymentConfirmationService confirmations, ObjectMapper mapper) {
        this.confirmations = confirmations;
        this.mapper = mapper;
    }

    @KafkaListener(topics = PaymentEvents.TOPIC, groupId = "reservation-payments")
    public void on(ConsumerRecord<String, String> record) throws JsonProcessingException {
        EventEnvelope env = mapper.readValue(record.value(), EventEnvelope.class);
        switch (env.eventType()) {
            case PaymentEvents.CAPTURED ->
                    confirmations.onCaptured(env.eventId(), mapper.treeToValue(env.payload(), PaymentCaptured.class));
            case PaymentEvents.FAILED ->
                    confirmations.onFailed(env.eventId(), mapper.treeToValue(env.payload(), PaymentFailed.class));
            default -> log.debug("ignoring payment event type {}", env.eventType());
        }
    }
}
