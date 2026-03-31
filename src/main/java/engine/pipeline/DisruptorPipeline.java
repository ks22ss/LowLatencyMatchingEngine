package engine.pipeline;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.PhasedBackoffWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import engine.domain.Order;
import engine.domain.OrderType;
import engine.domain.Side;
import engine.matching.OrderBook;
import engine.metrics.EngineMetrics;

import java.util.Locale;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Disruptor pipeline: single producer (or multi later for Netty), single consumer (matching).
 * Producers publish SUBMIT or CANCEL events; consumer runs OrderBook and forwards trades.
 * Ring buffer is pre-allocated; no allocation in the hot path when publishing or consuming.
 * <p>
 * Wait strategy defaults to phased spin/yield then lite blocking (see {@link #createWaitStrategy()});
 * override with {@code -Ddisruptor.wait.strategy=…} or {@code DISRUPTOR_WAIT_STRATEGY} for latency vs CPU.
 */
public final class DisruptorPipeline {

    private final Disruptor<InboundEvent> disruptor;
    private final RingBuffer<InboundEvent> ringBuffer;
    private final OrderBook orderBook;
    private final EngineMetrics metrics;

    public DisruptorPipeline(int ringSize, TradeListener tradeListener, ThreadFactory threadFactory) {
        this(ringSize, tradeListener, threadFactory, EngineMetrics.noop());
    }

    public DisruptorPipeline(int ringSize, TradeListener tradeListener, ThreadFactory threadFactory, EngineMetrics metrics) {
        this.metrics = metrics;
        this.orderBook = new OrderBook();
        InboundEventFactory factory = new InboundEventFactory();
        disruptor = new Disruptor<>(
                factory,
                ringSize,
                threadFactory,
                ProducerType.SINGLE,
                createWaitStrategy()
        );
        disruptor.handleEventsWith(new MatchingEventHandler(orderBook, tradeListener, metrics));
        disruptor.start();
        ringBuffer = disruptor.getRingBuffer();
    }

    /** Publish a submit event (order). Returns true if published; false if ring full (should not happen with proper backpressure). */
    public void publishSubmit(Order order) {
        long sequence = ringBuffer.next();
        try {
            InboundEvent event = ringBuffer.get(sequence);
            event.setSubmit(
                    order.orderId(),
                    order.side(),
                    order.price(),
                    order.quantity(),
                    order.orderType(),
                    // Use monotonic time as the baseline for in-process latency measurements.
                    // This avoids relying on client epoch timestamps.
                    System.nanoTime()
            );
        } finally {
            ringBuffer.publish(sequence);
        }
    }

    /** Convenience: publish limit order with timestamp 0. */
    public void publishSubmit(long orderId, Side side, long price, long quantity) {
        publishSubmit(Order.of(orderId, side, price, quantity, OrderType.LIMIT));
    }

    /** Publish a cancel event. */
    public void publishCancel(long orderId) {
        long sequence = ringBuffer.next();
        try {
            InboundEvent event = ringBuffer.get(sequence);
            event.setCancel(orderId);
        } finally {
            ringBuffer.publish(sequence);
        }
    }

    public void shutdown() {
        disruptor.shutdown();
    }

    /** Expose for tests that need to assert on book state (e.g. after draining). */
    public OrderBook getOrderBook() {
        return orderBook;
    }

    /**
     * Selects how the matching thread waits for new ring events. {@link BlockingWaitStrategy} avoids burning CPU
     * when idle but pays kernel wake-up cost on each publish, which hurts sub-10 microsecond tail latency under load.
     * <p>
     * Order: system property {@code disruptor.wait.strategy}, then env {@code DISRUPTOR_WAIT_STRATEGY}.
     * Values (case-insensitive; hyphens allowed): {@code blocking}, {@code yielding}, {@code busy_spin},
     * {@code phased} (default: phased lite lock — spin, yield, then {@link com.lmax.disruptor.LiteBlockingWaitStrategy}).
     */
    static WaitStrategy createWaitStrategy() {
        String raw = System.getProperty("disruptor.wait.strategy");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("DISRUPTOR_WAIT_STRATEGY");
        }
        if (raw == null || raw.isBlank()) {
            return PhasedBackoffWaitStrategy.withLiteLock(1, 10, TimeUnit.MICROSECONDS);
        }
        String key = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (key) {
            case "blocking" -> new BlockingWaitStrategy();
            case "yielding" -> new YieldingWaitStrategy();
            case "busy_spin", "busyspin", "spin" -> new BusySpinWaitStrategy();
            case "phased", "phased_lite" -> PhasedBackoffWaitStrategy.withLiteLock(1, 10, TimeUnit.MICROSECONDS);
            default -> throw new IllegalArgumentException(
                    "Unknown disruptor.wait.strategy / DISRUPTOR_WAIT_STRATEGY: '"
                            + raw
                            + "'. Expected: blocking, yielding, busy_spin, phased.");
        };
    }

    private static final class InboundEventFactory implements com.lmax.disruptor.EventFactory<InboundEvent> {
        @Override
        public InboundEvent newInstance() {
            return new InboundEvent();
        }
    }
}
