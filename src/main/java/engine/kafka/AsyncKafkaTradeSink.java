package engine.kafka;

import engine.domain.TradeEvent;
import engine.pipeline.TradeListener;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * Offloads Kafka I/O from the matching thread: {@link #onTrades} only does non-blocking
 * {@link ArrayBlockingQueue#offer} per trade. A daemon thread serializes and {@code send}s.
 * <p>
 * On full queue, trades are dropped and {@link #droppedTrades()} increments (observable via metrics later).
 */
public final class AsyncKafkaTradeSink implements TradeListener, AutoCloseable {

    private final KafkaProducer<byte[], byte[]> producer;
    private final String topic;
    private final ArrayBlockingQueue<TradeEvent> queue;
    private final Thread sender;
    private volatile boolean running = true;
    private final AtomicLong dropped = new AtomicLong();
    private final LongConsumer kafkaDroppedReporter;
    private final ByteBuffer reuseBuffer = ByteBuffer.allocateDirect(TradeEventBinaryEncoder.BYTES);
    private final ByteBuffer keyBuffer = ByteBuffer.allocateDirect(Long.BYTES);

    public AsyncKafkaTradeSink(KafkaSinkConfig config) {
        this(config, null);
    }

    /**
     * @param kafkaDroppedReporter optional callback invoked with the number of dropped trades.
     *                              Must not block (called on matching thread).
     */
    public AsyncKafkaTradeSink(KafkaSinkConfig config, LongConsumer kafkaDroppedReporter) {
        this.topic = config.topic();
        this.queue = new ArrayBlockingQueue<>(config.queueCapacity());
        this.kafkaDroppedReporter = kafkaDroppedReporter;
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        p.put(ProducerConfig.ACKS_CONFIG, "1");
        p.put(ProducerConfig.LINGER_MS_CONFIG, "0");
        p.put(ProducerConfig.BATCH_SIZE_CONFIG, "16384");
        this.producer = new KafkaProducer<>(p);
        this.sender = new Thread(this::senderLoop, "kafka-trade-sender");
        this.sender.setDaemon(true);
        this.sender.start();
    }

    @Override
    public void onTrades(List<TradeEvent> trades) {
        for (TradeEvent t : trades) {
            if (!queue.offer(t)) {
                dropped.incrementAndGet();
                if (kafkaDroppedReporter != null) {
                    kafkaDroppedReporter.accept(1L);
                }
            }
        }
    }

    private void senderLoop() {
        while (running || !queue.isEmpty()) {
            try {
                TradeEvent t = queue.poll(100, TimeUnit.MILLISECONDS);
                if (t == null) {
                    continue;
                }
                TradeEventBinaryEncoder.encode(t, reuseBuffer);
                byte[] value = new byte[TradeEventBinaryEncoder.BYTES];
                reuseBuffer.flip();
                reuseBuffer.get(value);
                byte[] key = tradeIdKeyBytes(t.tradeId());
                producer.send(new ProducerRecord<>(topic, key, value));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Kafka send error: " + e.getMessage());
            }
        }
    }

    private byte[] tradeIdKeyBytes(long tradeId) {
        keyBuffer.clear();
        keyBuffer.putLong(tradeId);
        keyBuffer.flip();
        byte[] key = new byte[Long.BYTES];
        keyBuffer.get(key);
        return key;
    }

    public long droppedTrades() {
        return dropped.get();
    }

    @Override
    public void close() {
        running = false;
        sender.interrupt();
        try {
            sender.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        producer.flush();
        producer.close();
    }
}
