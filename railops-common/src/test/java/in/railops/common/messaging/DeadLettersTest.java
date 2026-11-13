package in.railops.common.messaging;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DeadLettersTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void malformed_events_skip_retries_land_in_the_consumer_scoped_dlt_and_are_counted() {
        KafkaOperations template = mock(KafkaOperations.class);
        when(template.isTransactional()).thenReturn(false);
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        var meters = new SimpleMeterRegistry();
        var handler = DeadLetters.errorHandler(template, meters, "payment");

        var rec = new ConsumerRecord<>("reservation.booking.v1", 7, 42L, "1234567890", "{not json");
        boolean handled = handler.handleOne(new IllegalArgumentException("payload field 'pnr' missing"), rec,
                mock(org.apache.kafka.clients.consumer.Consumer.class),
                mock(org.springframework.kafka.listener.MessageListenerContainer.class));

        assertThat(handled).as("not retryable: recovered on the first failure").isTrue();
        var sent = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("reservation.booking.v1.payment.dlt");
        assertThat(sent.getValue().partition()).isEqualTo(7);
        assertThat(meters.get("kafka.dead_lettered").tags("topic", "reservation.booking.v1", "consumer", "payment")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void consumer_names_are_validated_so_dlt_topics_stay_predictable() {
        for (String bad : List.of("", "Payment", "pay.ment", "../x")) {
            assertThatThrownBy(() -> DeadLetters.errorHandler(mock(KafkaOperations.class), new SimpleMeterRegistry(), bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
