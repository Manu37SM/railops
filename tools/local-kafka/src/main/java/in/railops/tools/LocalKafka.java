package in.railops.tools;

import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Local single-node Kafka (KRaft) on localhost:9092. Topics are NOT created here: each service declares
 * its own topics (partitions, retention) via KafkaAdmin, exactly as against a real cluster, so this tool
 * can't hide a missing topic definition.
 */
public final class LocalKafka {

    private LocalKafka() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9092;
        EmbeddedKafkaKraftBroker broker = new EmbeddedKafkaKraftBroker(1, 6);
        broker.kafkaPorts(port);
        broker.brokerProperties(Map.of(
                "auto.create.topics.enable", "false",   // a typo'd topic name must fail, not silently create a topic
                "transaction.state.log.replication.factor", "1",
                "transaction.state.log.min.isr", "1",
                "offsets.topic.replication.factor", "1",
                "log.retention.hours", "168"));
        broker.afterPropertiesSet();
        // With KRaft on spring-kafka 3.3 the requested port isn't honoured (the test kit picks its own),
        // so publish the real address for the services to use as KAFKA_BOOTSTRAP.
        String brokers = broker.getBrokersAsString();
        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "railops-kafka.bootstrap"), brokers);
        System.out.println("LOCAL KAFKA READY at " + brokers);
        Runtime.getRuntime().addShutdownHook(new Thread(broker::destroy));
        new CountDownLatch(1).await();
    }
}
