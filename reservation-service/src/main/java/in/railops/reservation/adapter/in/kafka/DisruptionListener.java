package in.railops.reservation.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.railops.common.messaging.EventEnvelope;
import in.railops.common.messaging.Topics;
import in.railops.reservation.application.RunCancellationService;
import in.railops.reservation.application.RunCancellationService.RunCancelled;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Bulk refunds for cancelled runs. Redelivery-safe: the bulk job resumes from its cursor or no-ops if done. */
@Component
public class DisruptionListener {

    private final RunCancellationService runCancellations;
    private final ObjectMapper mapper;

    public DisruptionListener(RunCancellationService runCancellations, ObjectMapper mapper) {
        this.runCancellations = runCancellations;
        this.mapper = mapper;
    }

    @KafkaListener(topics = Topics.DISRUPTION, groupId = "reservation-disruption")
    public void on(ConsumerRecord<String, String> record) throws Exception {
        EventEnvelope env = mapper.readValue(record.value(), EventEnvelope.class);
        if ("RunCancelled".equals(env.eventType())) {
            runCancellations.process(mapper.treeToValue(env.payload(), RunCancelled.class));
        }
    }
}
