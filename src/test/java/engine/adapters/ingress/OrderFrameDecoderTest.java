package engine.adapters.ingress;

import engine.core.domain.OrderType;
import engine.core.domain.Side;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderFrameDecoderTest {

    @Test
    void decodesSubmitFrame() {
        EmbeddedChannel ch = new EmbeddedChannel(new OrderFrameDecoder());
        ByteBuf buf = Unpooled.buffer(WireProtocol.LEN_SUBMIT);
        buf.writeByte(WireProtocol.MSG_SUBMIT);
        buf.writeLong(100L);
        buf.writeByte(WireProtocol.sideToByte(Side.BUY));
        buf.writeLong(100_00L);
        buf.writeLong(10L);
        buf.writeByte(WireProtocol.orderTypeToByte(OrderType.LIMIT));
        buf.writeLong(0L);
        ch.writeInbound(buf);

        List<Object> out = new ArrayList<>();
        for (;;) {
            Object o = ch.readInbound();
            if (o == null) break;
            out.add(o);
        }
        assertEquals(1, out.size());
        InboundCommand.Submit sub = (InboundCommand.Submit) out.get(0);
        assertEquals(100L, sub.orderId());
        assertEquals(Side.BUY, sub.side());
        assertEquals(100_00L, sub.price());
        assertEquals(10L, sub.quantity());
        assertEquals(OrderType.LIMIT, sub.orderType());
    }

    @Test
    void decodesCancelFrame() {
        EmbeddedChannel ch = new EmbeddedChannel(new OrderFrameDecoder());
        ByteBuf buf = Unpooled.buffer(WireProtocol.LEN_CANCEL);
        buf.writeByte(WireProtocol.MSG_CANCEL);
        buf.writeLong(42L);
        ch.writeInbound(buf);

        InboundCommand.Cancel cancel = (InboundCommand.Cancel) ch.readInbound();
        assertNotNull(cancel);
        assertEquals(42L, cancel.orderId());
    }

    @Test
    void doesNotDecodePartialFrame() {
        EmbeddedChannel ch = new EmbeddedChannel(new OrderFrameDecoder());
        ByteBuf buf = Unpooled.buffer(5);
        buf.writeByte(WireProtocol.MSG_SUBMIT);
        buf.writeInt(0);
        ch.writeInbound(buf);
        assertNull(ch.readInbound());
    }
}

