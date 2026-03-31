package engine.adapters.kafka;

import engine.core.domain.TradeEvent;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class TradeEventBinaryEncoderTest {

    @Test
    void encodeRoundTripLongs() {
        TradeEvent t = new TradeEvent(1L, 100_00L, 5L, 10L, 20L, 999L);
        byte[] bytes = TradeEventBinaryEncoder.encode(t);
        assertEquals(TradeEventBinaryEncoder.BYTES, bytes.length);
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        assertEquals(1L, buf.getLong());
        assertEquals(100_00L, buf.getLong());
        assertEquals(5L, buf.getLong());
        assertEquals(10L, buf.getLong());
        assertEquals(20L, buf.getLong());
        assertEquals(999L, buf.getLong());
    }
}

