package engine.domain;

/**
 * Immutable order. All numeric fields use primitives for allocation-friendly hot path.
 * Record: immutable by default, no setters, so safe to share across threads (e.g. from Disruptor to matcher). 
 * Compact representation and canonical constructor reduce allocation.
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
public record Order(
        long orderId,
        Side side,
        long price,
        long quantity,
        OrderType orderType,
        long timestampNanos
) {
    public Order {
        if (quantity <= 0) {
            // We need to fail fast here to avoid the order being added to the book.
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (orderType == OrderType.LIMIT && price <= 0) {
            // We need to fail fast here to avoid the order being added to the book.
            throw new IllegalArgumentException("limit order price must be positive");
        }
    }

    /** Avoids long timestamp in call sites when latency capture is not needed. */
    public static Order of(long orderId, Side side, long price, long quantity, OrderType orderType) {
        return new Order(orderId, side, price, quantity, orderType, 0L);
    }
}
