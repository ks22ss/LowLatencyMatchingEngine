# Runbook

## Prerequisites

- **JDK 21** (LTS) for Gradle and runtime. Do not use JDK 25 to run Gradle on this project until the build toolchain supports it (see README).
- Optional: Docker for local Kafka / Prometheus / Grafana.

## Build and test

```bash
./gradlew build          # Windows: gradlew.bat build
./gradlew test
```

Microbenchmarks (JMH):

```bash
./gradlew jmh
# Results also under build/results/jmh/results.txt
```

## Disruptor consumer wait strategy

The matching thread blocks or spins waiting for ring events. **`BlockingWaitStrategy`** minimizes CPU when idle but adds scheduler wake-up latency — poor for aggressive tail targets under load.

| `DISRUPTOR_WAIT_STRATEGY` / `-Ddisruptor.wait.strategy` | Behavior |
| -------------------------------------------------------- | -------- |
| *(unset)* | **Default:** phased spin → yield → lite lock (`PhasedBackoffWaitStrategy.withLiteLock(1µs, 10µs, …)`). |
| `phased` | Same as default. |
| `busy_spin` | `BusySpinWaitStrategy` — lowest publish-to-handler latency, pins a core under load. |
| `yielding` | `YieldingWaitStrategy` — middle ground. |
| `blocking` | `BlockingWaitStrategy` — dev / low CPU. |

System property wins when both are set.

## Run the engine

```bash
./gradlew run
# Optional: pass listen port as first arg (default 9999)
./gradlew run --args="9999"
```

- **Ingress**: TCP binary protocol on the engine port (default **9999**); see README **Wire protocol**.
- **Metrics**: HTTP Prometheus scrape on **8081** by default (`METRICS_PORT` or `-Dmetrics.port`). Ring overload: **`matching.ring.publish.rejected.total`** (`op=submit` or `cancel`) increments when the Disruptor ring is full; the ingress handler **closes** that TCP connection.
- **Kafka**: set `KAFKA_BOOTSTRAP_SERVERS` or `-Dkafka.bootstrap.servers` to enable async trade publishing (topic and queue size in README).

## Docker: Kafka + Prometheus + Grafana

From the repo root:

```bash
docker compose up -d
```

Typical ports (see `docker-compose.yml`):

| Service | Port |
|---------|------|
| Kafka | 9092 |
| Prometheus | 9090 |
| Grafana | 3000 (default admin/admin in compose) |

Point the app at Kafka with `KAFKA_BOOTSTRAP_SERVERS=localhost:9092` (or host IP from inside containers). Prometheus in this repo is configured to scrape `host.docker.internal` for engine metrics when the app runs on the host.

## Verify

1. **Health**: process listens on configured engine port; `/metrics` returns Prometheus text on the metrics port.
2. **Functional**: run `./gradlew test`; use a TCP client or integration test path that sends SUBMIT/CANCEL frames.
3. **Kafka** (if enabled): consume `engine-trades` (or `KAFKA_TRADES_TOPIC`) and confirm 48-byte-per-message layout (see `TradeEventBinaryEncoder` / README).

## Generate load (Go)

The repo includes a Go TCP load generator that speaks the same binary wire protocol as the Netty ingress.

From the repo root:

```bash
cd scripts/loadgen
go run . -addr 127.0.0.1:9999 -duration 60s -conns 4 -rate 20000
```

To run at maximum speed (no target rate), set `-rate 0`.

## AWS experiment (Terraform)

For the cloud experiment (engine + spot loadgens + self-hosted Prometheus/Grafana), see:

- `infra/README.md`
- `README.md` → “AWS observability & chaos experiment (Terraform)”

## Troubleshooting

| Symptom | Check |
|---------|--------|
| Gradle fails on JDK 25 | Use JDK 21 for `gradlew` (JAVA_HOME or `org.gradle.java.home`). |
| Metrics bind error | App logs and falls back to noop metrics; fix port clash or permissions. |
| No Kafka messages | Bootstrap URL, topic ACLs, and whether the matcher produced trades. |
| Dropped trades | Queue full under load; increase capacity or scale consumer; monitor future drop metric. |

## References

- **[PRD.md](PRD.md)** — scope and observability.
- **[DESIGN.md](DESIGN.md)** — architecture.
