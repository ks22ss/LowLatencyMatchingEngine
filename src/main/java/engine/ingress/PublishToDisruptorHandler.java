package engine.ingress;

import engine.domain.Order;
import engine.pipeline.DisruptorPipeline;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * Publishes decoded InboundCommand to the Disruptor. Runs on the Netty I/O thread;
 * must not block. {@link DisruptorPipeline#publishSubmit} / {@link DisruptorPipeline#publishCancel}
 * use {@code tryNext}: if the ring is full the publish fails, metrics record a reject, and the channel
 * is closed so the client gets a clear slow-consumer signal.
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
                if (!pipeline.publishSubmit(order)) {
                    ctx.close();
                }
            }
            case InboundCommand.Cancel c -> {
                if (!pipeline.publishCancel(c.orderId())) {
                    ctx.close();
                }
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}
