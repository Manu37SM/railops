package in.railops.common.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.config.TopicBuilder;

import java.time.Duration;

/**
 * The topic catalogue. Every topic has an owner (the only producer); the owner declares it. Keys:
 * <ul>
 *   <li>reservation.booking.v1 / payment.events.v1: key = PNR, so all events of one booking stay ordered</li>
 *   <li>reservation.inventory.v1 / ops.disruption.v1: key = trainRunId (promotion and bulk work per run)</li>
 *   <li>audit.events.v1: key = actor id</li>
 * </ul>
 * Each consumed topic has a {@code .dlt} twin with the same partition count, because DeadLetterPublishingRecoverer
 * writes to the same partition number.
 */
public final class Topics {

    public static final String BOOKING = "reservation.booking.v1";
    public static final String INVENTORY = "reservation.inventory.v1";
    public static final String PAYMENT = "payment.events.v1";
    public static final String DISRUPTION = "ops.disruption.v1";
    public static final String AUDIT = "audit.events.v1";

    /**
     * Optional upper bound on every topic's partition count, from the system property or environment variable
     * {@value #MAX_PARTITIONS}. Unset in compose and k8s (full counts). Set on Render: Confluent Basic includes only
     * 30 partitions per eCKU, and the full catalogue (120) would bill three extra eCKUs. The same cap applies to a
     * topic and its {@code .dlt} twin, so they keep equal partition counts.
     */
    public static final String MAX_PARTITIONS = "RAILOPS_KAFKA_MAX_PARTITIONS";

    private Topics() {
    }

    public static NewTopic topic(String name, int partitions, int replicas, Duration retention) {
        return TopicBuilder.name(name).partitions(capped(partitions, maxPartitions())).replicas(replicas)
                .config("retention.ms", Long.toString(retention.toMillis()))
                .config("min.insync.replicas", Integer.toString(Math.min(2, replicas)))
                .build();
    }

    public static NewTopic dlt(String name, int partitions, int replicas) {
        // DLTs keep messages long enough for a human to look at them after a long weekend.
        return topic(name + ".dlt", partitions, replicas, Duration.ofDays(14));
    }

    static int capped(int partitions, int max) {
        return max > 0 ? Math.min(partitions, max) : partitions;
    }

    static int maxPartitions() {
        String v = System.getProperty(MAX_PARTITIONS, System.getenv(MAX_PARTITIONS));
        if (v == null || v.isBlank()) {
            return 0;
        }
        try {
            int n = Integer.parseInt(v.trim());
            if (n < 1) {
                throw new IllegalStateException(MAX_PARTITIONS + " must be at least 1, was " + v);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalStateException(MAX_PARTITIONS + " must be a number, was " + v, e);
        }
    }
}
