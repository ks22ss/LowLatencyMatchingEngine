package engine.app.pipeline;

import engine.core.domain.OrderType;
import engine.core.domain.Side;

/**
 * Mutable event for the Disruptor ring buffer. Pre-allocated; producer sets fields and
 * publishes; consumer reads and clears nothing (overwritten on next claim). Holds either
 * a submit (order fields) or cancel (orderId only). Avoids allocation in the hot path.
 */
public final class InboundEvent {

    private InboundEventType type;
    private long orderId;
    private Side side;
    private long price;
    private long quantity;
    private OrderType orderType;
    private long timestampNanos;

    public InboundEventType getType() {
        return type;
    }

    public void setType(InboundEventType type) {
        this.type = type;
    }

    public long getOrderId() {
        return orderId;
    }

    public void setOrderId(long orderId) {
        this.orderId = orderId;
    }

    public Side getSide() {
        return side;
    }

    public void setSide(Side side) {
        this.side = side;
    }

    public long getPrice() {
        return price;
    }

    public void setPrice(long price) {
        this.price = price;
    }

    public long getQuantity() {
        return quantity;
    }

    public void setQuantity(long quantity) {
        this.quantity = quantity;
    }

    public OrderType getOrderType() {
        return orderType;
    }

    public void setOrderType(OrderType orderType) {
        this.orderType = orderType;
    }

    public long getTimestampNanos() {
        return timestampNanos;
    }

    public void setTimestampNanos(long timestampNanos) {
        this.timestampNanos = timestampNanos;
    }

    /** Set all fields for a SUBMIT event. */
    public void setSubmit(long orderId, Side side, long price, long quantity, OrderType orderType, long timestampNanos) {
        this.type = InboundEventType.SUBMIT;
        this.orderId = orderId;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.orderType = orderType;
        this.timestampNanos = timestampNanos;
    }

    /** Set for a CANCEL event. */
    public void setCancel(long orderId) {
        this.type = InboundEventType.CANCEL;
        this.orderId = orderId;
    }
}

