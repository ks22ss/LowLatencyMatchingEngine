package engine;

import engine.pipeline.DisruptorPipeline;
import engine.pipeline.TradeListener;

/**
 * Entry point for the low-latency matching engine. Starts the Disruptor pipeline
 * (single consumer = matching thread); producers can publish via pipeline.publishSubmit/Cancel.
 */
public final class MatchingEngineApp {

    public static void main(String[] args) throws InterruptedException {
        // Disruptor pipeline configuration
        // 1 << 16  =  2^16  =  65,536 ring buffer size
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
        Runtime.getRuntime().addShutdownHook(new Thread(pipeline::shutdown));
        System.out.println("Matching engine running (ring size=" + ringSize + "). Ctrl+C to stop.");
        Thread.sleep(Long.MAX_VALUE);
    }
}
