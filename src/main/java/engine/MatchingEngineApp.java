package engine;

import engine.ingress.IngressServer;
import engine.pipeline.DisruptorPipeline;
import engine.pipeline.TradeListener;

/**
 * Entry point for the low-latency matching engine. Starts the Disruptor pipeline and
 * Netty ingress (TCP decode → publish). Single consumer = matching thread.
 */
public final class MatchingEngineApp {

    private static final int DEFAULT_PORT = 9999;

    public static void main(String[] args) throws InterruptedException {
        int ringSize = 1 << 16;
        TradeListener listener = TradeListener.noOp();
        DisruptorPipeline pipeline = new DisruptorPipeline(
                ringSize,
                listener,
                r -> {
                    Thread t = new Thread(r, "matching");
                    t.setDaemon(false);
                    return t;
                }
        );
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        IngressServer server = new IngressServer(pipeline, port);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.shutdown();
            pipeline.shutdown();
        }));
        System.out.println("Matching engine listening on port " + port + " (ring size=" + ringSize + "). Ctrl+C to stop.");
        Thread.sleep(Long.MAX_VALUE);
    }
}
