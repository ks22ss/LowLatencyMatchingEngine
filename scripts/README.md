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
- `-batch`: max frames per `Write` syscall (default `128`; try `64`–`256` for throughput)
- `-mode`: preset workload (`crossing`, `rest-heavy`, `cancel-heavy`, `market-heavy`); see table below
- `-rest-spread`: half-spread for `rest-heavy` (default **2000** when that mode is active)
- `-cancel-pct`: percent of messages that are CANCEL (0..100); used only when `-mode` is unset
- `-market-pct`: percent of SUBMITs that are MARKET (0..100); used only when `-mode` is unset
- `-price`, `-price-jitter`: base price and uniform jitter
- `-qty-min`, `-qty-max`: quantity range
- `-seed`: RNG seed for repeatability

While running, it prints **`[progress] interval_msg/s=…`** every **5s** (rolling window since the last line).

### Preset modes (`-mode`)

If **`-mode`** is set, it picks workload shape and fixed **`-cancel-pct` / `-market-pct`** (and **`rest-spread`** for rest-heavy). For fully manual mixes, omit **`-mode`** and set **`-cancel-pct`** / **`-market-pct`** yourself.

| `-mode` | Behavior |
|---------|-----------|
| `crossing` | Alternating **LIMIT** BUY and SELL at the same peg price (`-price`, optional `-price-jitter`) so each leg crosses the resting opposite; ~5% cancels. |
| `rest-heavy` | Random **LIMIT** side; bids at `price - rest-spread`, asks at `price + rest-spread` (default spread **2000** if `-rest-spread` omitted); sparse book, little crossing. |
| `cancel-heavy` | ~**45%** CANCELs (valid IDs from recent SUBMITs); rest random limits like default. |
| `market-heavy` | ~**50%** **MARKET** SUBMITs (~5% cancels); random BUY/SELL sizes. |

Aliases: `crossing-pairs`, `cross` → `crossing`; `rest-heavy` → `rest`; `cancel-heavy` → `cancel`; `market-heavy` → `market`.

### Examples

Sustained crossing-ish flow (random BUY/SELL) at a single price:

```powershell
cd scripts\loadgen
go run . -addr 127.0.0.1:9999 -duration 30m -conns 10 -rate 50000 -price 10000 -qty-min 1 -qty-max 3
```

Cancel-heavy:

```powershell
go run . -addr 127.0.0.1:9999 -duration 30m -conns 10 -rate 50000 -cancel-pct 30
```

Market-heavy (manual %):

```powershell
go run . -addr 127.0.0.1:9999 -duration 30m -conns 10 -rate 50000 -market-pct 25
```

Preset modes:

```powershell
go run . -addr 127.0.0.1:9999 -duration 2m -conns 4 -rate 0 -mode crossing -price 10000
go run . -addr 127.0.0.1:9999 -duration 2m -conns 4 -rate 0 -mode rest-heavy -price 10000
go run . -addr 127.0.0.1:9999 -duration 2m -conns 4 -rate 0 -mode cancel-heavy
go run . -addr 127.0.0.1:9999 -duration 2m -conns 4 -rate 0 -mode market-heavy
```

