package engine.adapters.ingress;

import engine.app.pipeline.DisruptorPipeline;
import engine.app.pipeline.TradeListener;
import engine.core.domain.OrderType;
import engine.core.domain.Side;
import engine.core.domain.TradeEvent;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class IngressIntegrationTest {

    private DisruptorPipeline pipeline;
    private IngressServer server;
    private NioEventLoopGroup clientGroup;
    private int boundPort;
    private final List<TradeEvent> collected = new ArrayList<>();
    private CountDownLatch tradeLatch;

    @BeforeEach
    void setUp() throws InterruptedException {
        tradeLatch = new CountDownLatch(1);
        TradeListener listener = t -> {
            collected.addAll(t);
            tradeLatch.countDown();
        };
        pipeline = new DisruptorPipeline(1024, listener, r -> {
            Thread t = new Thread(r, "matching");
            t.setDaemon(true);
            return t;
        });
        server = new IngressServer(pipeline, 0);
        boundPort = server.start();
        clientGroup = new NioEventLoopGroup(1);
    }

    @AfterEach
    void tearDown() {
        if (clientGroup != null) clientGroup.shutdownGracefully();
        if (server != null) server.shutdown();
        if (pipeline != null) pipeline.shutdown();
    }

    @Test
    void clientSubmitsTwoOrdersThatMatchProducesTrade() throws Exception {
        Bootstrap bootstrap = new Bootstrap();
        var ch = bootstrap.group(clientGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) {}
                })
                .connect(new InetSocketAddress("127.0.0.1", boundPort))
                .sync()
                .channel();

        ByteBuf submitSell = Unpooled.buffer(WireProtocol.LEN_SUBMIT);
        submitSell.writeByte(WireProtocol.MSG_SUBMIT);
        submitSell.writeLong(1L);
        submitSell.writeByte(WireProtocol.sideToByte(Side.SELL));
        submitSell.writeLong(100_00L);
        submitSell.writeLong(5L);
        submitSell.writeByte(WireProtocol.orderTypeToByte(OrderType.LIMIT));
        submitSell.writeLong(0L);
        ch.writeAndFlush(submitSell).await();

        ByteBuf submitBuy = Unpooled.buffer(WireProtocol.LEN_SUBMIT);
        submitBuy.writeByte(WireProtocol.MSG_SUBMIT);
        submitBuy.writeLong(2L);
        submitBuy.writeByte(WireProtocol.sideToByte(Side.BUY));
        submitBuy.writeLong(100_00L);
        submitBuy.writeLong(5L);
        submitBuy.writeByte(WireProtocol.orderTypeToByte(OrderType.LIMIT));
        submitBuy.writeLong(0L);
        ch.writeAndFlush(submitBuy).await();

        assertTrue(tradeLatch.await(2, TimeUnit.SECONDS));
        assertEquals(1, collected.size());
        assertEquals(100_00L, collected.get(0).price());
        assertEquals(5L, collected.get(0).quantity());
    }
}

