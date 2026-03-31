package engine.adapters.metrics;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import engine.core.domain.OrderType;
import engine.core.domain.Side;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Micrometer Prometheus implementation with a lightweight {@code /metrics} endpoint.
 *
 * <p>Matching hot path only calls:
 * <ul>
 *   <li>{@link #onSubmit}</li>
 *   <li>{@link #onCancel}</li>
 *   <li>{@link #onTrades}</li>
 *   <li>{@link #onRingPublishRejectedSubmit} / {@link #onRingPublishRejectedCancel} (ingress thread)</li>
 * </ul>
 * Those operations are designed to be cheap (counter increments + a single timer record).
 */
public final class PrometheusEngineMetrics implements EngineMetrics, AutoCloseable {

    private final PrometheusMeterRegistry registry;
    private final HttpServer server;
    private final JvmGcMetrics jvmGcMetrics;

    private final Timer submitLatency;

    private final Counter submitBuyLimit;
    private final Counter submitBuyMarket;
    private final Counter submitSellLimit;
    private final Counter submitSellMarket;

    private final Counter cancelTotal;
    private final Counter tradesTotal;
    private final Counter kafkaDroppedTotal;
    private final Counter ringPublishRejectedSubmit;
    private final Counter ringPublishRejectedCancel;

    public PrometheusEngineMetrics(int metricsPort) throws IOException {
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        // JVM / host metrics (heap, GC pauses, threads, CPU) for diagnosing tail latency.
        new ClassLoaderMetrics().bindTo(registry);
        new JvmMemoryMetrics().bindTo(registry);
        this.jvmGcMetrics = new JvmGcMetrics();
        this.jvmGcMetrics.bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);

        this.submitLatency = Timer.builder("matching.submit.to.match.latency")
                .description("Time from ring publish to matching thread processing")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofNanos(1_000)) // 1 us
                .maximumExpectedValue(Duration.ofMillis(10))   // 10 ms
                .register(registry);

        // Pre-create the small set of tagged series (2 sides * 2 order types).
        this.submitBuyLimit = Counter.builder("matching.inbound.submit.total")
                .tag("side", "BUY")
                .tag("orderType", "LIMIT")
                .register(registry);
        this.submitBuyMarket = Counter.builder("matching.inbound.submit.total")
                .tag("side", "BUY")
                .tag("orderType", "MARKET")
                .register(registry);
        this.submitSellLimit = Counter.builder("matching.inbound.submit.total")
                .tag("side", "SELL")
                .tag("orderType", "LIMIT")
                .register(registry);
        this.submitSellMarket = Counter.builder("matching.inbound.submit.total")
                .tag("side", "SELL")
                .tag("orderType", "MARKET")
                .register(registry);

        this.cancelTotal = Counter.builder("matching.inbound.cancel.total").register(registry);
        this.tradesTotal = Counter.builder("matching.trades.filled.total").register(registry);
        this.kafkaDroppedTotal = Counter.builder("matching.kafka.trades.dropped.total").register(registry);
        this.ringPublishRejectedSubmit = Counter.builder("matching.ring.publish.rejected.total")
                .tag("op", "submit")
                .register(registry);
        this.ringPublishRejectedCancel = Counter.builder("matching.ring.publish.rejected.total")
                .tag("op", "cancel")
                .register(registry);

        this.server = HttpServer.create(new InetSocketAddress(metricsPort), 0);
        this.server.createContext("/metrics", this::handleMetrics);
        this.server.setExecutor(null);
        this.server.start();
    }

    @Override
    public void onSubmit(Side side, OrderType orderType, long latencyNanos) {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(orderType, "orderType");

        if (side == Side.BUY) {
            if (orderType == OrderType.LIMIT) {
                submitBuyLimit.increment();
            } else {
                submitBuyMarket.increment();
            }
        } else {
            if (orderType == OrderType.LIMIT) {
                submitSellLimit.increment();
            } else {
                submitSellMarket.increment();
            }
        }

        if (latencyNanos > 0) {
            submitLatency.record(latencyNanos, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void onCancel() {
        cancelTotal.increment();
    }

    @Override
    public void onTrades(int tradeCount) {
        if (tradeCount > 0) {
            tradesTotal.increment(tradeCount);
        }
    }

    @Override
    public void onKafkaDroppedTrades(long droppedCount) {
        if (droppedCount > 0) {
            kafkaDroppedTotal.increment(droppedCount);
        }
    }

    @Override
    public void onRingPublishRejectedSubmit() {
        ringPublishRejectedSubmit.increment();
    }

    @Override
    public void onRingPublishRejectedCancel() {
        ringPublishRejectedCancel.increment();
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        String body = registry.scrape();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
        jvmGcMetrics.close();
        registry.close();
    }
}

