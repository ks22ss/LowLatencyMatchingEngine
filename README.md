# Low-Latency Order Matching Engine

Java matching engine (single symbol, limit + market) targeting **1M orders/sec** and **p99 &lt; 10 µs**. Built with LMAX Disruptor, Netty, and Kafka for trade-event replay.

See **[docs/PRD.md](docs/PRD.md)** for requirements and observability (JMH, HdrHistogram, Micrometer, JFR, async-profiler).

## Requirements

- **JDK 25** (project uses Java 25 toolchain; adjust in `build.gradle.kts` if using 21 or 17)
- Gradle 8.10+ (wrapper included)

## Build & run

```bash
# Build and test
./gradlew build

# Run the application (placeholder)
./gradlew run
```

On Windows: `gradlew.bat` instead of `./gradlew`.

## Layout

- `src/main/java/engine/` — core engine (matching, Disruptor, Netty, Kafka sink)
- `src/main/resources/` — config placeholder
- `src/test/java/` — unit tests and (later) JMH benchmarks

## Key dependencies

| Purpose        | Library        |
|----------------|----------------|
| Event pipeline | LMAX Disruptor |
| Network I/O    | Netty          |
| Trade sink     | Kafka clients  |
| Metrics        | Micrometer + Prometheus |
| Latency stats  | HdrHistogram   |
| Benchmarks     | JMH (test scope) |
