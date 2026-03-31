package engine.adapters.metrics;

import engine.core.domain.OrderType;
import engine.core.domain.Side;

/**
 * Metrics abstraction for the matching hot path.
 * <p>
 * Matching thread should stay allocation-free and avoid blocking; metrics calls are
 * implemented with cheap counters/timers.
 */
public interface EngineMetrics {

    void onSubmit(Side side, OrderType orderType, long latencyNanos);

    void onCancel();

    void onTrades(int tradeCount);

    void onKafkaDroppedTrades(long droppedCount);

    /** Ingress could not acquire a ring slot (slow matcher). Called from the producer thread (e.g. Netty I/O). */
    void onRingPublishRejectedSubmit();

    /** Same as {@link #onRingPublishRejectedSubmit()} for cancel commands. */
    void onRingPublishRejectedCancel();

    static EngineMetrics noop() {
        return NoopEngineMetrics.INSTANCE;
    }

    /** No-op implementation to keep tests and low-overhead modes fast. */
    final class NoopEngineMetrics implements EngineMetrics {
        private static final NoopEngineMetrics INSTANCE = new NoopEngineMetrics();

        private NoopEngineMetrics() {}

        @Override
        public void onSubmit(Side side, OrderType orderType, long latencyNanos) {}

        @Override
        public void onCancel() {}

        @Override
        public void onTrades(int tradeCount) {}

        @Override
        public void onKafkaDroppedTrades(long droppedCount) {}

        @Override
        public void onRingPublishRejectedSubmit() {}

        @Override
        public void onRingPublishRejectedCancel() {}
    }
}

