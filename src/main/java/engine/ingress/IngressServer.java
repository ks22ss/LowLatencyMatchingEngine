package engine.ingress;

import engine.pipeline.DisruptorPipeline;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;

/**
 * Netty TCP server: accepts connections, decodes order/cancel frames, publishes to Disruptor.
 * Single worker thread so the pipeline remains single-producer.
 */
public final class IngressServer {

    private final DisruptorPipeline pipeline;
    private final int port;
    private NioEventLoopGroup bossGroup;
    private NioEventLoopGroup workerGroup;

    public IngressServer(DisruptorPipeline pipeline, int port) {
        this.pipeline = pipeline;
        this.port = port;
    }

    /** Starts the server; returns the bound port (same as constructor port, or ephemeral if port was 0). */
    public int start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(1);
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new OrderFrameDecoder()); // First read and decode the byte into Command
                        p.addLast(new PublishToDisruptorHandler(pipeline)); // Then publish the Command to the Disruptor
                    }
                });
        var bindFuture = b.bind(new InetSocketAddress(port)).sync();
        return ((InetSocketAddress) bindFuture.channel().localAddress()).getPort();
    }

    public void shutdown() {
        if (bossGroup != null) bossGroup.shutdownGracefully();
        if (workerGroup != null) workerGroup.shutdownGracefully();
    }
}
