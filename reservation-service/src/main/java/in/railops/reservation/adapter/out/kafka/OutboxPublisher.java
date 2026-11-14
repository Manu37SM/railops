package in.railops.reservation.adapter.out.kafka;

import tools.jackson.databind.ObjectMapper;
import in.railops.common.messaging.OutboxRelay;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Schedules the shared {@link OutboxRelay} for the reservation schema. */
@Component
public class OutboxPublisher {

    private final OutboxRelay relay;

    public OutboxPublisher(JdbcClient jdbc, TransactionTemplate tx, KafkaTemplate<String, String> kafka,
                           ObjectMapper mapper, MeterRegistry meters,
                           @Value("${railops.outbox.batch-size:500}") int batch) {
        this.relay = new OutboxRelay(jdbc, tx, kafka, mapper, meters, "reservation", batch);
    }

    @Scheduled(fixedDelayString = "${railops.outbox.poll-ms:100}")
    public void poll() {
        relay.poll();
    }
}
