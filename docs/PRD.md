# Product requirements (PRD)

## Summary

Single-symbol **limit and market** order matching in Java with a bias toward **throughput and tail latency**: design targets include on the order of **1M orders/sec** and **p99 &lt; 10 µs** on tuned hardware for the hot matching path. The full stack (Netty ingress, Disruptor handoff, optional Kafka) adds latency outside that microbenchmark scope.

## Functional scope

- **Order book**: price–time priority; FIFO within a price level; partial fills preserve the maker’s queue position (same resting instance, in-place quantity update).
- **Order types**: `LIMIT`, `MARKET`.
- **Ingress**: TCP binary protocol (see README **Wire protocol**).
- **Matching**: single-threaded consumer on an LMAX Disruptor ring; no locks on the book in the happy path.

## Non-goals (current codebase)

- Multi-symbol routing or persistence of book state.
- Order amendment beyond cancel; no post-only / iceberg types in core API.
- Guaranteed delivery of trades to Kafka (async bounded queue; drops under back-pressure).

## Observability roadmap

| Area | Status / direction |
|------|-------------------|
| Submit / match latency (e.g. HdrHistogram) | Pipeline records timestamps; metrics via Micrometer/Prometheus (`EngineMetrics`). |
| Kafka sink drops | Hook exists (`onKafkaDroppedTrades`); expose as counter/gauge for alerting. |
| Book depth / REST admin | Not in scope today; PRD placeholder for future ops APIs. |

## References

- **[DESIGN.md](DESIGN.md)** — components and threading.
- **[RUNBOOK.md](RUNBOOK.md)** — build, run, Docker stack.
