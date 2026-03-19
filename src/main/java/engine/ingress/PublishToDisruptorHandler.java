package engine.ingress;

import engine.domain.Order;
import engine.pipeline.DisruptorPipeline;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * Publishes decoded InboundCommand to the Disruptor. Runs on the Netty I/O thread;
 * must not block. ringBuffer.next() can block if the ring is full (backpressure).
 */
public final class PublishToDisruptorHandler extends SimpleChannelInboundHandler<InboundCommand> {

    private final DisruptorPipeline pipeline;

    public PublishToDisruptorHandler(DisruptorPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, InboundCommand cmd) {
        switch (cmd) {
            case InboundCommand.Submit s -> {
                Order order = new Order(
                        s.orderId(), s.side(), s.price(), s.quantity(), s.orderType(), s.timestampNanos()
                );
                pipeline.publishSubmit(order);
            }
            case InboundCommand.Cancel c -> pipeline.publishCancel(c.orderId());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}
