# Low-Latency Order Matching Engine

Java matching engine (single symbol, limit + market) targeting **1M orders/sec** and **p99 &lt; 10 µs**. Built with LMAX Disruptor, Netty, and Kafka for trade-event replay.

See **[docs/PRD.md](docs/PRD.md)** for requirements and observability (JMH, HdrHistogram, Micrometer, JFR, async-profiler).

## Requirements

- **JDK 21** (LTS) to run Gradle and build the project. The app is compiled for Java 21.
- Gradle 8.10+ (wrapper included)

**Why not JDK 25?** If you run `gradlew` with JDK 25, the build fails with `IllegalArgumentException: 25.0.2` (Gradle’s Kotlin DSL doesn’t support JDK 25’s version string yet). Use JDK 21 to run Gradle; you can still have JDK 25 installed for other work.

## Build & run

**Use JDK 21 when running Gradle** (required):

- **Option A — set JAVA_HOME** (recommended):  
  Point `JAVA_HOME` to JDK 21, then run Gradle:
  ```bash
  # Windows (PowerShell)
  $env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
  .\gradlew.bat build

  # Windows (CMD)
  set JAVA_HOME=C:\Program Files\Java\jdk-21
  gradlew.bat build
  ```
- **Option B — force in project**:  
  In `gradle.properties`, set (use your actual JDK 21 path):
  ```properties
  org.gradle.java.home=C:/Program Files/Java/jdk-21
  ```

Then:

```bash
# Build and test
./gradlew build

# Run the application (placeholder)
./gradlew run
```

On Windows use `gradlew.bat` instead of `./gradlew`.

## Layout

- `src/main/java/engine/` — core engine (matching, Disruptor, Netty, Kafka sink)
- `src/main/resources/` — config placeholder
- `src/test/java/` — unit tests and (later) JMH benchmarks

## Wire protocol (TCP ingress)

Binary, big-endian. Send to the engine port (default 9999).

- **SUBMIT** (type `0`): 1 + 8 + 1 + 8 + 8 + 1 + 8 = 35 bytes: type, orderId, side (0=BUY 1=SELL), price, quantity, orderType (0=LIMIT 1=MARKET), timestampNanos.
- **CANCEL** (type `1`): 1 + 8 = 9 bytes: type, orderId.

## Key dependencies

| Purpose        | Library        |
|----------------|----------------|
| Event pipeline | LMAX Disruptor |
| Network I/O    | Netty          |
| Trade sink     | Kafka clients  |
| Metrics        | Micrometer + Prometheus |
| Latency stats  | HdrHistogram   |
| Benchmarks     | JMH (test scope) |
