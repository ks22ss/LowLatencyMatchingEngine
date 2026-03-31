package engine.app;

import engine.adapters.ingress.IngressServer;
import engine.adapters.kafka.AsyncKafkaTradeSink;
import engine.adapters.kafka.KafkaSinkConfig;
import engine.adapters.metrics.EngineMetrics;
import engine.adapters.metrics.PrometheusEngineMetrics;
import engine.app.pipeline.DisruptorPipeline;
import engine.app.pipeline.TradeListener;

import java.util.Optional;

/**
 * Entry point for the low-latency matching engine. Starts the Disruptor pipeline and
 * Netty ingress (TCP decode → publish). Optional async Kafka trade sink (off matching thread).
 */
public final class MatchingEngineApp {

    private static final int DEFAULT_PORT = 9999;
    private static final int DEFAULT_METRICS_PORT = 8081;

    public static void main(String[] args) throws InterruptedException {
        int ringSize = 1 << 16;

        int metricsPort = Integer.parseInt(
                Optional.ofNullable(System.getenv("METRICS_PORT"))
                        .orElse(System.getProperty("metrics.port", String.valueOf(DEFAULT_METRICS_PORT)))
        );

        EngineMetrics metrics;
        try {
            metrics = new PrometheusEngineMetrics(metricsPort);
        } catch (Exception e) {
            // Keep the engine runnable even if metrics bind fails.
            System.err.println("Metrics disabled (failed to start Prometheus endpoint): " + e.getMessage());
            metrics = EngineMetrics.noop();
        }
        final EngineMetrics metricsFinal = metrics;

        Optional<KafkaSinkConfig> kafkaConfig = KafkaSinkConfig.tryFromEnv();
        AsyncKafkaTradeSink kafkaSink = kafkaConfig
                .map(cfg -> new AsyncKafkaTradeSink(cfg, d -> metricsFinal.onKafkaDroppedTrades(d)))
                .orElse(null);
        TradeListener listener = kafkaSink != null ? kafkaSink::onTrades : TradeListener.noOp();

        DisruptorPipeline pipeline = new DisruptorPipeline(
                ringSize,
                listener,
                r -> {
                    Thread t = new Thread(r, "matching");
                    t.setDaemon(false);
                    return t;
                },
                metricsFinal
        );
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        IngressServer server = new IngressServer(pipeline, port);
        int boundPort = server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.shutdown();
            pipeline.shutdown();
            if (kafkaSink != null) {
                kafkaSink.close();
            }
            if (metricsFinal instanceof AutoCloseable ac) {
                try {
                    ac.close();
                } catch (Exception ignored) {
                }
            }
        }));

        if (kafkaSink != null) {
            System.out.println("Kafka trade sink enabled (async queue → producer).");
        }
        System.out.println("Metrics enabled on http://0.0.0.0:" + metricsPort + "/metrics");
        System.out.println("Matching engine listening on port " + boundPort + " (ring size=" + ringSize + "). Ctrl+C to stop.");
        Thread.sleep(Long.MAX_VALUE);
    }
}

