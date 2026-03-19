package engine.ingress;

import engine.domain.OrderType;
import engine.domain.Side;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * Decodes wire frames into InboundCommand. Reads type byte then payload; big-endian.
 * Does not allocate ByteBuf slices; reads directly so the pipeline can release the buffer.
 */
public final class OrderFrameDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 1) {
            return;
        }
        byte type = in.getByte(in.readerIndex());
        int needed = type == WireProtocol.MSG_SUBMIT ? WireProtocol.LEN_SUBMIT : WireProtocol.LEN_CANCEL;
        if (in.readableBytes() < needed) {
            return;
        }
        in.skipBytes(1);
        if (type == WireProtocol.MSG_SUBMIT) {
            long orderId = in.readLong();
            Side side = WireProtocol.sideFromByte(in.readByte());
            long price = in.readLong();
            long quantity = in.readLong();
            OrderType orderType = WireProtocol.orderTypeFromByte(in.readByte());
            long timestampNanos = in.readLong();
            out.add(new InboundCommand.Submit(orderId, side, price, quantity, orderType, timestampNanos));
        } else {
            long orderId = in.readLong();
            out.add(new InboundCommand.Cancel(orderId));
        }
    }
}
