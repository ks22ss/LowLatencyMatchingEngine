# Load generation scripts

This repo uses a **binary TCP wire protocol** (see `engine.adapters.ingress.WireProtocol`) and a
Netty ingress server. For production-like testing (many concurrent clients and sustained load),
use the Go load generator below.

## Go load generator (`scripts/loadgen`)

From repo root:

```powershell
cd scripts\loadgen
go run . -addr 127.0.0.1:9999 -duration 60s -conns 4 -rate 20000
```

### Important flags

- `-addr`: engine ingress `host:port` (default `127.0.0.1:9999`)
- `-duration`: how long to run
- `-conns`: number of concurrent TCP connections
- `-rate`: target total messages/sec across all connections (`0` = as fast as possible)
- `-cancel-pct`: percent of messages that are CANCEL (0..100)
- `-market-pct`: percent of SUBMITs that are MARKET (0..100)
- `-price`, `-price-jitter`: base price and uniform jitter
- `-qty-min`, `-qty-max`: quantity range
- `-seed`: RNG seed for repeatability

### Examples

Sustained crossing-ish flow (random BUY/SELL) at a single price:

```powershell
cd scripts\loadgen
go run . -addr 127.0.0.1:9999 -duration 2m -conns 10 -rate 50000 -price 10000 -qty-min 1 -qty-max 3
```

Cancel-heavy:

```powershell
go run . -addr 127.0.0.1:9999 -duration 2m -conns 10 -rate 50000 -cancel-pct 30
```

Market-heavy:

```powershell
go run . -addr 127.0.0.1:9999 -duration 2m -conns 10 -rate 50000 -market-pct 25
```

