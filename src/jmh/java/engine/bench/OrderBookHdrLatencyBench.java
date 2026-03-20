package engine.bench;

import engine.domain.Order;
import engine.domain.OrderType;
import engine.domain.Side;
import engine.matching.OrderBook;
import org.HdrHistogram.Histogram;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.io.PrintStream;
import java.util.concurrent.TimeUnit;

/**
 * Records each {@link OrderBook#submit} latency into an {@link Histogram} and prints
 * percentiles at the end of each trial (stdout). Single-threaded so the histogram is accurate.
 */
@Fork(1)
@Threads(1)
/** No JMH warmup iterations: we JIT-warm in {@link #setup} then allocate a fresh histogram. */
@Warmup(iterations = 0)
@Measurement(iterations = 5, time = 1)
@State(Scope.Benchmark)
public class OrderBookHdrLatencyBench {

    private static final long HIGHEST_TRACKABLE_NS = TimeUnit.SECONDS.toNanos(1);

    private OrderBook book;
    private long nextId;
    private Histogram histogram;

    @Setup
    public void setup() {
        book = new OrderBook();
        nextId = 1L;
        // Manual warmup so C2 + data structures are hot before we record into HdrHistogram.
        for (int i = 0; i < 200_000; i++) {
            book.submit(Order.of(nextId++, Side.BUY, 100_00L, 1L, OrderType.LIMIT));
        }
        histogram = new Histogram(HIGHEST_TRACKABLE_NS, 3);
    }

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void submitLimitRest() {
        long start = System.nanoTime();
        book.submit(Order.of(nextId++, Side.BUY, 100_00L, 1L, OrderType.LIMIT));
        long elapsed = System.nanoTime() - start;
        if (elapsed > HIGHEST_TRACKABLE_NS) {
            elapsed = HIGHEST_TRACKABLE_NS;
        }
        histogram.recordValue(elapsed);
    }

    @TearDown(Level.Trial)
    public void printHistogram() {
        PrintStream out = System.out;
        out.println();
        out.println("=== HdrHistogram: OrderBookHdrLatencyBench.submitLimitRest (per trial) ===");
        histogram.outputPercentileDistribution(out, 1.0);
        out.println("Total count: " + histogram.getTotalCount());
        out.println();
    }
}
