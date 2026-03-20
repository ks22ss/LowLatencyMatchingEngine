package engine.pipeline;

import com.lmax.disruptor.EventHandler;
import engine.domain.Order;
import engine.domain.OrderType;
import engine.domain.TradeEvent;
import engine.matching.MatchResult;
import engine.matching.OrderBook;
import engine.metrics.EngineMetrics;

import java.util.List;

/**
 * Single-threaded consumer: takes InboundEvent from the ring, calls OrderBook.submit or
 * cancel, and forwards any trades to TradeListener. Runs on the matching thread; no locks.
 * One Order allocation per SUBMIT (built from event fields); trades are passed through.
 */
public final class MatchingEventHandler implements EventHandler<InboundEvent> {

    private final OrderBook orderBook;
    private final TradeListener tradeListener;
    private final EngineMetrics metrics;

    public MatchingEventHandler(OrderBook orderBook, TradeListener tradeListener, EngineMetrics metrics) {
        this.orderBook = orderBook;
        this.tradeListener = tradeListener;
        this.metrics = metrics;
    }

    @Override
    public void onEvent(InboundEvent event, long sequence, boolean endOfBatch) {
        if (event.getType() == InboundEventType.SUBMIT) {
            Order order = new Order(
                    event.getOrderId(),
                    event.getSide(),
                    event.getPrice(),
                    event.getQuantity(),
                    event.getOrderType(),
                    event.getTimestampNanos()
            );
            MatchResult result = orderBook.submit(order);

            long afterMatchingNanos = System.nanoTime();
            long latencyNanos = afterMatchingNanos - event.getTimestampNanos();
            metrics.onSubmit(event.getSide(), event.getOrderType(), latencyNanos);

            List<TradeEvent> trades = result.trades();
            metrics.onTrades(trades.size());
            if (!trades.isEmpty()) tradeListener.onTrades(trades);
        } else {
            orderBook.cancel(event.getOrderId());
            metrics.onCancel();
        }
    }
}
