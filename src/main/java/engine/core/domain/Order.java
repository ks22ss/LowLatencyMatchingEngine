package engine.core.domain;

/**
 * Order submitted to the engine. Identity fields are fixed; {@code quantity} may decrease in place
 * while the order rests in the book after a partial fill so price-level queue order stays FIFO
 * (see {@link engine.core.matching.OrderBook}).
 * <p>
 * All numeric fields use primitives for allocation-friendly hot path. New instances are created
 * per submit on the matching thread; resting orders are updated by mutation, not replacement.
 * long for price/quantity: no float rounding in matching; use fixed-point (e.g. basis points).
 * long for ids: dense, cache-friendly; no Long boxing in collections. timestampNanos:
 * nanosecond resolution for latency stats (p99/p999); 0 when not needed to avoid syscalls.
 * Validation in constructor: fail-fast before the order enters the ring buffer.
 *
 * @param orderId      unique order id (used for cancel and book lookup)
 * @param side         BUY or SELL
 * @param price        limit price (e.g. cents or basis points); ignored for MARKET
 * @param quantity     order size (e.g. lots or units)
 * @param orderType    LIMIT or MARKET
 * @param timestampNanos optional; for latency measurement (epoch nanos)
 */
public final class Order {

    private final long orderId;
    private final Side side;
    private final long price;
    private long quantity;
    private final OrderType orderType;
    private final long timestampNanos;

    public Order(
            long orderId,
            Side side,
            long price,
            long quantity,
            OrderType orderType,
            long timestampNanos
    ) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (orderType == OrderType.LIMIT && price <= 0) {
            throw new IllegalArgumentException("limit order price must be positive");
        }
        this.orderId = orderId;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.orderType = orderType;
        this.timestampNanos = timestampNanos;
    }

    /** Avoids long timestamp in call sites when latency capture is not needed. */
    public static Order of(long orderId, Side side, long price, long quantity, OrderType orderType) {
        return new Order(orderId, side, price, quantity, orderType, 0L);
    }

    public long orderId() {
        return orderId;
    }

    public Side side() {
        return side;
    }

    public long price() {
        return price;
    }

    public long quantity() {
        return quantity;
    }

    public OrderType orderType() {
        return orderType;
    }

    public long timestampNanos() {
        return timestampNanos;
    }

    /**
     * Decrements quantity after a partial match against this resting maker. Same {@link Order}
     * instance stays in the price level’s queue to preserve time priority.
     */
    public void reduceQuantity(long fillQty) {
        if (fillQty <= 0) {
            throw new IllegalArgumentException("fill quantity must be positive");
        }
        if (fillQty > quantity) {
            throw new IllegalArgumentException("fill quantity exceeds remaining quantity");
        }
        quantity -= fillQty;
    }
}
