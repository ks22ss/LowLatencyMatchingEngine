# Architecture & design

## High-level flow

```text
[Client TCP] --Netty--> OrderFrameDecoder --> InboundEvent
                                              |
                                       Disruptor (publish)
                                              |
                                     MatchingEventHandler (1 thread)
                                              |
                                     OrderBook.submit / cancel
                                              |
                            trades --> TradeListener --> (optional) AsyncKafkaTradeSink
```

- **Ingress** (`engine.ingress`): Netty decodes frames into `InboundEvent` (SUBMIT / CANCEL) and publishes to the ring.
- **Pipeline** (`engine.pipeline`): LMAX Disruptor with a dedicated **matching** thread running `MatchingEventHandler`.
  The consumer **wait strategy** defaults to **phased** (busy spin, then yield, then lite blocking) so steady traffic avoids full `BlockingWaitStrategy` wake-up cost; set `DISRUPTOR_WAIT_STRATEGY` or `-Ddisruptor.wait.strategy` to `busy_spin` (lowest tail latency, highest CPU), `yielding`, `blocking`, or `phased` (see RUNBOOK).
- **Core** (`engine.matching`): `OrderBook` — `TreeMap` price levels, `LinkedHashMap` per level for FIFO by insertion order.
- **Orders** (`engine.domain`): Resting makers use **in-place** `Order.reduceQuantity` on partial fill so `LinkedHashMap` order is unchanged (strict price–time).
- **Trades**: `TradeEvent` list per submit; listener runs on the matching thread for `onTrades`; Kafka path only **offers** to a queue so the matcher does not block on I/O.

## Threading model

| Thread | Role |
|--------|------|
| Netty I/O | Decode TCP, publish to Disruptor. |
| `matching` | Sole owner of `OrderBook`; emits metrics and trade callbacks. |
| `kafka-trade-sender` (if enabled) | Drains trade queue, serializes, `KafkaProducer.send`. |

Single writer to the book; no concurrent mutation of resting `Order` instances except from the matching thread.

## Kafka trade sink

- Config from env / system properties (`KafkaSinkConfig`).
- Bounded queue between matcher and producer; **drops** when full (see `AsyncKafkaTradeSink`).

## Trade-offs

- **Disruptor** vs direct queues: bounded, wait-free publish from Netty; predictable consumer batching.
- **In-process book**: minimal indirection; snapshot/recovery is an external concern.
- **JMH benches** under `src/jmh` isolate `OrderBook`; they do not prove end-to-end SLOs.

## Related docs

- **[PRD.md](PRD.md)** — requirements and observability.
- **[RUNBOOK.md](RUNBOOK.md)** — operations.
