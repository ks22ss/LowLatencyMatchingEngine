package engine.domain;

/**
 * Immutable trade (fill) event. Emitted when an incoming order matches a resting order.
 * <p>
 * Primitives only: no reference types, so serialization (e.g. to Kafka) can be a flat
 * buffer or fixed schema; no GC from this object in the hot path. maker/taker: standard
 * exchange terminology; maker = liquidity provider (resting), taker = aggressor; needed
 * for audit and downstream position/risk. tradeId: globally unique fill id for idempotent
 * replay and deduplication when consuming the trade stream.
 *
 * @param tradeId       unique fill id
 * @param price         execution price
 * @param quantity      filled quantity
 * @param makerOrderId  resting order that was matched
 * @param takerOrderId  incoming order that took liquidity
 * @param timestampNanos epoch nanos when fill occurred
 */
public record TradeEvent(
        long tradeId,
        long price,
        long quantity,
        long makerOrderId,
        long takerOrderId,
        long timestampNanos
) {
    public TradeEvent {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (price <= 0) {
            throw new IllegalArgumentException("price must be positive");
        }
    }
}
