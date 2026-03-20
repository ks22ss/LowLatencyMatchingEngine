package engine.kafka;

import java.util.Optional;

/**
 * Kafka sink settings. Matching thread only touches queue offer; I/O on kafka-sender thread.
 */
public record KafkaSinkConfig(
        String bootstrapServers,
        String topic,
        int queueCapacity
) {
    public KafkaSinkConfig {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("bootstrapServers required");
        }
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic required");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
    }

    /** Enabled when {@code KAFKA_BOOTSTRAP_SERVERS} or {@code -Dkafka.bootstrap.servers} is set. */
    public static Optional<KafkaSinkConfig> tryFromEnv() {
        String bootstrap = firstNonBlank(
                System.getenv("KAFKA_BOOTSTRAP_SERVERS"),
                System.getProperty("kafka.bootstrap.servers")
        );
        if (bootstrap == null) {
            return Optional.empty();
        }
        String topic = firstNonBlank(
                System.getenv("KAFKA_TRADES_TOPIC"),
                System.getProperty("kafka.trades.topic")
        );
        if (topic == null) {
            topic = "engine-trades";
        }
        int cap = parseInt(
                System.getenv("KAFKA_TRADES_QUEUE_CAPACITY"),
                System.getProperty("kafka.trades.queue.capacity"),
                65_536
        );
        return Optional.of(new KafkaSinkConfig(bootstrap, topic, cap));
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static int parseInt(String env, String prop, int defaultValue) {
        String s = firstNonBlank(env, prop);
        if (s == null) {
            return defaultValue;
        }
        return Integer.parseInt(s);
    }
}
