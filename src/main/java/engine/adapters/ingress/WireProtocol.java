package engine.adapters.ingress;

import engine.core.domain.OrderType;
import engine.core.domain.Side;

/**
 * Binary wire protocol: fixed layout, big-endian. Enables zero-copy or minimal-copy decode.
 * SUBMIT = 0, CANCEL = 1. Side: 0=BUY, 1=SELL. OrderType: 0=LIMIT, 1=MARKET.
 */
public final class WireProtocol {

    private WireProtocol() {}

    public static final byte MSG_SUBMIT = 0;
    public static final byte MSG_CANCEL = 1;

    /** SUBMIT: type(1) + orderId(8) + side(1) + price(8) + quantity(8) + orderType(1) + timestampNanos(8) = 35 */
    public static final int LEN_SUBMIT = 1 + 8 + 1 + 8 + 8 + 1 + 8;
    /** CANCEL: type(1) + orderId(8) = 9 */
    public static final int LEN_CANCEL = 1 + 8;

    public static Side sideFromByte(byte b) {
        return b == 0 ? Side.BUY : Side.SELL;
    }

    public static byte sideToByte(Side s) {
        return (byte) (s == Side.BUY ? 0 : 1);
    }

    public static OrderType orderTypeFromByte(byte b) {
        return b == 0 ? OrderType.LIMIT : OrderType.MARKET;
    }

    public static byte orderTypeToByte(OrderType t) {
        return (byte) (t == OrderType.LIMIT ? 0 : 1);
    }
}

