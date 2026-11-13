package in.railops.common.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.time.Duration;

/**
 * The one Kafka consumer error policy for every service (was four hand-copied beans; only reservation counted).
 *
 * <ul>
 *   <li>Retries with exponential backoff, then publishes to the consumer-scoped DLT {@code <topic>.<consumer>.dlt},
 *       same partition (each topic's DLT twin has the same partition count, see {@link Topics#dlt}).</li>
 *   <li>Malformed input (JSON errors, IllegalArgumentException) goes straight to the DLT: retrying cannot fix it.</li>
 *   <li>Every dead letter increments {@code kafka.dead_lettered{topic,consumer}}. INC-003: a dead-lettered event is
 *       a customer whose state did not change; the alert must cover every consumer, not just the one that had the
 *       incident.</li>
 * </ul>
 */
public final class DeadLetters {

    public static final Duration DEFAULT_INITIAL = Duration.ofMillis(500);
    public static final Duration DEFAULT_MAX_ELAPSED = Duration.ofSeconds(15);

    private DeadLetters() {
    }

    public static DefaultErrorHandler errorHandler(KafkaOperations<?, ?> template, MeterRegistry meters, String consumer) {
        return errorHandler(template, meters, consumer, DEFAULT_INITIAL, DEFAULT_MAX_ELAPSED);
    }

    public static DefaultErrorHandler errorHandler(KafkaOperations<?, ?> template, MeterRegistry meters, String consumer,
                                                   Duration initial, Duration maxElapsed) {
        if (consumer == null || !consumer.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("consumer must be a lowercase service name, got " + consumer);
        }
        var dlt = new DeadLetterPublishingRecoverer(template,
                (rec, ex) -> new TopicPartition(dltTopic(rec.topic(), consumer), rec.partition()));
        ConsumerRecordRecoverer counted = (rec, ex) -> {
            Counter.builder("kafka.dead_lettered").tag("topic", rec.topic()).tag("consumer", consumer)
                    .register(meters).increment();
            dlt.accept(rec, ex);
        };
        var backoff = new ExponentialBackOff(initial.toMillis(), 2.0);
        backoff.setMaxElapsedTime(maxElapsed.toMillis());
        var handler = new DefaultErrorHandler(counted, backoff);
        handler.addNotRetryableExceptions(com.fasterxml.jackson.core.JsonProcessingException.class,
                IllegalArgumentException.class);
        return handler;
    }

    public static String dltTopic(String topic, String consumer) {
        return topic + "." + consumer + ".dlt";
    }
}
