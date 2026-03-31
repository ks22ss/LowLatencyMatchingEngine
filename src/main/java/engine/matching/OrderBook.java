package engine.matching;

import engine.domain.Order;
import engine.domain.Side;
import engine.domain.TradeEvent;
import engine.domain.OrderType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Single-symbol limit order book with price-time priority. Single-threaded only; no locks.
 * <p>
 * Bids: best = highest price (TreeMap reverse order); asks: best = lowest (natural order).
 * Within a price level, FIFO via LinkedHashMap insertion order. orderId → Order map gives
 * O(1) cancel lookup; we also need to remove from the level's map (O(1) with LinkedHashMap).
 */
public final class OrderBook {

    /** Bids: price descending so firstKey() is best bid. */
    private final TreeMap<Long, LinkedHashMap<Long, Order>> bids =
            new TreeMap<>(Comparator.reverseOrder());
    /** Asks: price ascending so firstKey() is best ask. */
    private final TreeMap<Long, LinkedHashMap<Long, Order>> asks = new TreeMap<>();
    /** Resting order by id for O(1) cancel and to know side/price when removing from level. */
    private final Map<Long, Order> restingById = new LinkedHashMap<>();
    private long nextTradeId = 1L;

    /**
     * Submit an order: match against the book, then rest if limit and quantity remains.
     * Price-time: we match at the resting order's price; within level, FIFO.
     */
    public MatchResult submit(Order order) {
        if (order.orderType() == OrderType.MARKET) {
            return matchOnly(order);
        }
        return matchAndMaybeRest(order);
    }

    /**
     * Cancel a resting order by id. Returns true if the order was found and removed.
     */
    public boolean cancel(long orderId) {
        Order order = restingById.remove(orderId);
        if (order == null) {
            return false;
        }
        TreeMap<Long, LinkedHashMap<Long, Order>> side = order.side() == Side.BUY ? bids : asks;
        LinkedHashMap<Long, Order> level = side.get(order.price());
        if (level != null) {
            level.remove(orderId);
            if (level.isEmpty()) {
                side.remove(order.price());
            }
        }
        return true;
    }

    /** Match incoming order against the book; no resting (market or remaining after match). */
    private MatchResult matchOnly(Order order) {
        List<TradeEvent> trades = new ArrayList<>();
        long remaining = order.quantity();
        long takerOrderId = order.orderId();
        long ts = System.nanoTime();

        if (order.side() == Side.BUY) {
            while (remaining > 0 && !asks.isEmpty()) {
                Long bestPrice = asks.firstKey();
                Fill fill = consumeFromLevel(asks, bestPrice, remaining, takerOrderId, Side.SELL, ts);
                if (fill == null) break;
                trades.add(fill.tradeEvent());
                remaining -= fill.fillQty();
            }
        } else {
            while (remaining > 0 && !bids.isEmpty()) {
                Long bestPrice = bids.firstKey();
                Fill fill = consumeFromLevel(bids, bestPrice, remaining, takerOrderId, Side.BUY, ts);
                if (fill == null) break;
                trades.add(fill.tradeEvent());
                remaining -= fill.fillQty();
            }
        }

        if (trades.isEmpty()) {
            return MatchResult.empty();
        }
        return MatchResult.of(trades, Optional.empty());
    }

    /** Match then rest if limit and quantity remains. */
    private MatchResult matchAndMaybeRest(Order order) {
        List<TradeEvent> trades = new ArrayList<>();
        long remaining = order.quantity();
        long takerOrderId = order.orderId();
        long ts = System.nanoTime();

        if (order.side() == Side.BUY) {
            while (remaining > 0 && !asks.isEmpty()) {
                Long bestPrice = asks.firstKey();
                if (bestPrice > order.price()) break;
                Fill fill = consumeFromLevel(asks, bestPrice, remaining, takerOrderId, Side.SELL, ts);
                if (fill == null) break;
                trades.add(fill.tradeEvent());
                remaining -= fill.fillQty();
            }
        } else {
            while (remaining > 0 && !bids.isEmpty()) {
                Long bestPrice = bids.firstKey();
                if (bestPrice < order.price()) break;
                Fill fill = consumeFromLevel(bids, bestPrice, remaining, takerOrderId, Side.BUY, ts);
                if (fill == null) break;
                trades.add(fill.tradeEvent());
                remaining -= fill.fillQty();
            }
        }

        if (remaining > 0) {
            Order rest = new Order(order.orderId(), order.side(), order.price(), remaining, order.orderType(), order.timestampNanos());
            addResting(rest);
            return MatchResult.of(trades, Optional.of(rest));
        }
        return trades.isEmpty() ? MatchResult.withRest(order) : MatchResult.of(trades, Optional.empty());
    }

    /** Take up to `remaining` from the best order at this level; create trade, update or remove resting. */
    private Fill consumeFromLevel(
            TreeMap<Long, LinkedHashMap<Long, Order>> side,
            long price,
            long remaining,
            long takerOrderId,
            Side makerSide,
            long ts
    ) {
        LinkedHashMap<Long, Order> level = side.get(price);
        if (level == null || level.isEmpty()) {
            side.remove(price);
            return null;
        }
        Order maker = level.values().iterator().next();
        long fillQty = Math.min(remaining, maker.quantity());
        long tradeId = nextTradeId++;
        TradeEvent trade = new TradeEvent(tradeId, price, fillQty, maker.orderId(), takerOrderId, ts);

        if (fillQty >= maker.quantity()) {
            level.remove(maker.orderId());
            restingById.remove(maker.orderId());
            if (level.isEmpty()) {
                side.remove(price);
            }
        } else {
            maker.reduceQuantity(fillQty);
        }

        return new Fill(trade, fillQty);
    }

    private void addResting(Order order) {
        TreeMap<Long, LinkedHashMap<Long, Order>> side = order.side() == Side.BUY ? bids : asks;
        side.computeIfAbsent(order.price(), k -> new LinkedHashMap<>()).put(order.orderId(), order);
        restingById.put(order.orderId(), order);
    }

    private record Fill(TradeEvent tradeEvent, long fillQty) {}
}
