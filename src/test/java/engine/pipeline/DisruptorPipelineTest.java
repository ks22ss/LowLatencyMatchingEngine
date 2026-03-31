package engine.pipeline;

import engine.domain.Order;
import engine.domain.OrderType;
import engine.domain.Side;
import engine.domain.TradeEvent;
import engine.matching.OrderBook;
import engine.metrics.EngineMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class DisruptorPipelineTest {

    private DisruptorPipeline pipeline;

    @AfterEach
    void tearDown() {
        if (pipeline != null) {
            pipeline.shutdown();
        }
    }

    @Test
    void publishSubmitRestsOrderThenMatches() throws InterruptedException {
        List<TradeEvent> collected = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        TradeListener listener = trades -> {
            collected.addAll(trades);
            latch.countDown();
        };
        pipeline = new DisruptorPipeline(64, listener, r -> {
            Thread t = new Thread(r, "matching");
            t.setDaemon(true);
            return t;
        });

        pipeline.publishSubmit(Order.of(1L, Side.SELL, 100_00L, 5L, OrderType.LIMIT));
        pipeline.publishSubmit(Order.of(2L, Side.BUY, 100_00L, 5L, OrderType.LIMIT));

        assertTrue(latch.await(2, TimeUnit.SECONDS), "expected trade callback");
        assertEquals(1, collected.size());
        assertEquals(100_00L, collected.get(0).price());
        assertEquals(5L, collected.get(0).quantity());
        assertEquals(1L, collected.get(0).makerOrderId());
        assertEquals(2L, collected.get(0).takerOrderId());
    }

    @Test
    void publishCancelRemovesRestingOrder() throws InterruptedException {
        List<TradeEvent> collected = new ArrayList<>();
        pipeline = new DisruptorPipeline(64, collected::addAll, r -> {
            Thread t = new Thread(r, "matching");
            t.setDaemon(true);
            return t;
        });

        pipeline.publishSubmit(Order.of(1L, Side.BUY, 100_00L, 10L, OrderType.LIMIT));
        Thread.sleep(50);
        pipeline.publishCancel(1L);
        Thread.sleep(50);

        OrderBook book = pipeline.getOrderBook();
        pipeline.publishSubmit(Order.of(2L, Side.SELL, 100_00L, 5L, OrderType.LIMIT));
        Thread.sleep(50);
        assertTrue(collected.isEmpty(), "cancel should have removed bid; sell should rest");
    }

    @Test
    void noOpListenerDoesNotThrow() {
        pipeline = new DisruptorPipeline(64, TradeListener.noOp(), r -> {
            Thread t = new Thread(r, "matching");
            t.setDaemon(true);
            return t;
        });
        assertTrue(pipeline.publishSubmit(Order.of(1L, Side.BUY, 100_00L, 10L, OrderType.LIMIT)));
        assertTrue(pipeline.publishCancel(1L));
    }

    @Test
    void publishSubmitReturnsFalseWhenRingFullRecordsMetric() throws InterruptedException {
        CountDownLatch consumerStalled = new CountDownLatch(1);
        CountDownLatch releaseStall = new CountDownLatch(1);
        AtomicBoolean stallOnce = new AtomicBoolean(true);
        AtomicLong rejects = new AtomicLong();

        EngineMetrics stallMetrics = new EngineMetrics() {
            @Override
            public void onSubmit(Side side, OrderType orderType, long latencyNanos) {
                if (stallOnce.compareAndSet(true, false)) {
                    consumerStalled.countDown();
                    try {
                        assertTrue(releaseStall.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                }
            }

            @Override
            public void onCancel() {}

            @Override
            public void onTrades(int tradeCount) {}

            @Override
            public void onKafkaDroppedTrades(long droppedCount) {}

            @Override
            public void onRingPublishRejectedSubmit() {
                rejects.incrementAndGet();
            }

            @Override
            public void onRingPublishRejectedCancel() {}
        };

        pipeline = new DisruptorPipeline(8, TradeListener.noOp(), r -> {
            Thread t = new Thread(r, "matching");
            t.setDaemon(true);
            return t;
        }, stallMetrics);

        assertTrue(pipeline.publishSubmit(Order.of(1L, Side.BUY, 100_00L, 1L, OrderType.LIMIT)));
        assertTrue(consumerStalled.await(5, TimeUnit.SECONDS), "consumer should stall on first submit");

        long nextId = 2L;
        while (pipeline.publishSubmit(Order.of(nextId++, Side.BUY, 100_00L, 1L, OrderType.LIMIT))) {
            if (nextId > 500) {
                releaseStall.countDown();
                fail("expected ring to report full before id 500");
            }
        }

        assertEquals(1L, rejects.get());
        releaseStall.countDown();
        Thread.sleep(50);
    }
}
