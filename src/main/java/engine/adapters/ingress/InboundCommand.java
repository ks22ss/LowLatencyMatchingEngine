package engine.adapters.ingress;

import engine.core.domain.OrderType;
import engine.core.domain.Side;

/**
 * Decoded inbound message from the wire. Immutable; decoder produces these, handler publishes to Disruptor.
 * Only two commands: SUBMIT and CANCEL.
 */
public sealed interface InboundCommand permits InboundCommand.Submit, InboundCommand.Cancel {

    record Submit(long orderId, Side side, long price, long quantity, OrderType orderType, long timestampNanos)
            implements InboundCommand {}

    record Cancel(long orderId) implements InboundCommand {}
}

