package engine.kafka;

import engine.domain.TradeEvent;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Fixed 48-byte payload: six big-endian longs (tradeId, price, qty, maker, taker, ts).
 * No schema registry; consumers can replay deterministically. Zero allocation when reusing buffer.
 */
public final class TradeEventBinaryEncoder {

    public static final int BYTES = 6 * Long.BYTES;

    private TradeEventBinaryEncoder() {}

    public static byte[] encode(TradeEvent t) {
        ByteBuffer buf = ByteBuffer.allocate(BYTES).order(ByteOrder.BIG_ENDIAN);
        putAll(buf, t);
        return buf.array();
    }

    public static void encode(TradeEvent t, ByteBuffer buf) {
        buf.clear();
        buf.order(ByteOrder.BIG_ENDIAN);
        putAll(buf, t);
    }

    private static void putAll(ByteBuffer buf, TradeEvent t) {
        buf.putLong(t.tradeId());
        buf.putLong(t.price());
        buf.putLong(t.quantity());
        buf.putLong(t.makerOrderId());
        buf.putLong(t.takerOrderId());
        buf.putLong(t.timestampNanos());
    }
}
