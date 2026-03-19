package engine.matching;

import engine.domain.Order;
import engine.domain.TradeEvent;

import java.util.List;
import java.util.Optional;

/**
 * Result of submitting an order: trades generated and any resting quantity.
 * <p>
 * Immutable. Empty list (not null) when no fills; restingOrder empty when order
 * fully filled or was market (no rest). Callers can iterate trades and optionally
 * handle resting without null checks.
 */
public record MatchResult(
        List<TradeEvent> trades,
        Optional<Order> restingOrder
) {
    private static final MatchResult EMPTY_NO_REST = new MatchResult(List.of(), Optional.empty());

    public MatchResult {
        trades = trades != null ? List.copyOf(trades) : List.of();
    }

    public static MatchResult empty() {
        return EMPTY_NO_REST;
    }

    public static MatchResult withTrades(List<TradeEvent> trades) {
        return new MatchResult(trades, Optional.empty());
    }

    public static MatchResult withRest(Order restingOrder) {
        return new MatchResult(List.of(), Optional.of(restingOrder));
    }

    public static MatchResult of(List<TradeEvent> trades, Optional<Order> restingOrder) {
        return new MatchResult(
                trades != null ? trades : List.of(),
                restingOrder != null ? restingOrder : Optional.empty()
        );
    }
}
