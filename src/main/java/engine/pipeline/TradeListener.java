package engine.pipeline;

import engine.domain.TradeEvent;

import java.util.List;

/**
 * Callback for trades produced by the matching engine. Implementations must not block;
 * e.g. hand off to another ring or async Kafka producer. Called on the matching thread.
 */
@FunctionalInterface
public interface TradeListener {

    void onTrades(List<TradeEvent> trades);

    /** No-op implementation for tests or when trade output is not needed. */
    static TradeListener noOp() {
        return trades -> {};
    }
}
