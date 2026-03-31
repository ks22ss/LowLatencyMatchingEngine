package engine.app.pipeline;

/**
 * Type of inbound event. SUBMIT = new order to match; CANCEL = remove resting by orderId.
 * Kept as enum for a single field in the event (no extra object in the ring).
 */
public enum InboundEventType {
    SUBMIT,
    CANCEL
}

