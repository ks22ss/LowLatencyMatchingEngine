package engine.bench;

import engine.domain.Order;
import engine.domain.OrderType;
import engine.domain.Side;
import engine.matching.OrderBook;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Throughput of {@link OrderBook#submit} when each order crosses (no resting inventory growth).
 */
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@State(Scope.Thread)
public class OrderBookThroughputBench {

    private OrderBook book;
    private long nextId;

    @Setup
    public void setup() {
        book = new OrderBook();
        nextId = 1L;
    }

    @TearDown
    public void tearDown() {
        book = null;
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void submitCrossingLimitOrders() {
        book.submit(Order.of(nextId++, Side.SELL, 100_00L, 1L, OrderType.LIMIT));
        book.submit(Order.of(nextId++, Side.BUY, 100_00L, 1L, OrderType.LIMIT));
    }
}
