package in.railops.common.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopicsTest {

    @AfterEach
    void clear() {
        System.clearProperty(Topics.MAX_PARTITIONS);
    }

    @Test
    void fullCountsWhenNoCapIsSet() {
        assertThat(Topics.topic(Topics.BOOKING, 12, 3, Duration.ofDays(7)).numPartitions()).isEqualTo(12);
    }

    @Test
    void capLowersEveryTopicAndItsDeadLetterTwinAlike() {
        System.setProperty(Topics.MAX_PARTITIONS, "2");
        NewTopic source = Topics.topic(Topics.PAYMENT, 12, 3, Duration.ofDays(7));
        NewTopic dlt = Topics.dlt(Topics.PAYMENT + ".reservation", 12, 3);
        assertThat(source.numPartitions()).isEqualTo(2);
        assertThat(dlt.numPartitions()).isEqualTo(source.numPartitions());
        assertThat(source.replicationFactor()).isEqualTo((short) 3);
    }

    @Test
    void capNeverRaisesASmallerCount() {
        assertThat(Topics.capped(1, 2)).isEqualTo(1);
        assertThat(Topics.capped(6, 0)).isEqualTo(6);
    }

    @Test
    void rejectsNonsense() {
        System.setProperty(Topics.MAX_PARTITIONS, "0");
        assertThatThrownBy(Topics::maxPartitions).isInstanceOf(IllegalStateException.class);
        System.setProperty(Topics.MAX_PARTITIONS, "two");
        assertThatThrownBy(Topics::maxPartitions).isInstanceOf(IllegalStateException.class);
    }
}
