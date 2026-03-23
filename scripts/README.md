# Order sender scripts

## Python (`send_orders.py`)

Uses **Python 3** and the **standard library only** (`struct` for big-endian frames). No pip install required.

1. Start the engine (from repo root, JDK 21):

   ```bash
   .\gradlew.bat run
   ```

2. In another terminal:

   ```bash
   cd scripts
   python send_orders.py match
   ```

   This sends a **SELL** limit then a **BUY** limit at the same price so they **cross** and produce a trade (same pattern as `IngressIntegrationTest`).

### Commands

| Command | Purpose |
|--------|---------|
| `match` | One crossing pair (customize `--sell-id`, `--buy-id`, `--price`, `--qty`) |
| `submit` | Single order: `--order-id`, `--side BUY\|SELL`, `--price`, `--qty`, optional `--order-type` |
| `cancel` | `--order-id` |
| `burst` | `--pairs N` crossing pairs for load smoke tests |

### Market simulator (`simulate_market.py`)

Poisson order arrivals, mean-reverting mid price, two-sided limit flow with random dispersion, and an **aggressive** fraction that posts **marketable limits** (simulated buyers/sellers competing for fills).

```bash
# From repo root (imports `send_orders` from the same folder)
python scripts/simulate_market.py --duration 60 --rate 150 --seed 7

# Max orders, as fast as possible (watch Disruptor / CPU)
python scripts/simulate_market.py --orders 200000 --rate 0 --progress 10000
```

Key knobs: `--buy-bias` (buy pressure), `--aggressive` (crossing intensity), `--mid-vol` / `--mean-reversion` / `--anchor-mid` (price dynamics), `--price-sigma` (limit dispersion), `--spread`.

### Options (`send_orders` subcommands)

- `--host` (default `127.0.0.1`)
- `--port` (default `9999`, must match engine listen port)
- `--timeout` connection timeout in seconds

### See results

- **Metrics:** `http://localhost:8081/metrics` (if the Prometheus endpoint started)
- **Kafka:** set `KAFKA_BOOTSTRAP_SERVERS` when starting the engine, create topic `engine-trades`, then consume (see [docs/RUNBOOK.md](../docs/RUNBOOK.md))

### Why Python

- Correct binary packing with `struct` on Windows and Unix
- Easy to extend (rate limiting, multiple connections, argparse)

### Plot trades from Kafka (`plot_trades_from_kafka.py`)

Requires **`pip install -r requirements-kafka-plot.txt`** (`kafka-python`, `matplotlib`). Decodes **48-byte** values (six big-endian `long`s) and plots **price vs time**.

#### Does Kafka “store trades on disk”? Can I replay?

Yes. Brokers append each topic partition to **log segments on disk** (configurable **`log.dirs`**). Messages stay available until **retention** deletes them (default is often **7 days** by time, and/or size limits). That is **not** unlimited history: old segments are removed per broker policy.

- **Docker:** Data usually lives **inside the container filesystem**. If you **`docker compose down`** and remove the container **without a named volume** for Kafka data, you can **lose** the log. For durable experiments, add a **volume** for the broker’s data directory.
- **“Subscribe” vs “replay”:** A consumer reads from an **offset**. “Live tail” starts near the end (`auto_offset_reset=latest`). Reading **stored** messages is still “subscribing” — you just start from **earliest** or **seek** to the beginning.
- **`--from-earliest`:** For a **new** consumer group (no committed offsets), start at the oldest **retained** message.
- **`--replay`:** After partitions are assigned, calls **`seek_to_beginning`** so you re-read the **retained** log even if this **`--group-id`** already had committed offsets (true **replay** for this run).

```bash
pip install -r scripts/requirements-kafka-plot.txt
python scripts/plot_trades_from_kafka.py --bootstrap localhost:9092 --topic engine-trades --from-earliest --max-messages 500 --output trades.png --no-show

# Replay full retained history with a stable group id:
python scripts/plot_trades_from_kafka.py --replay --group-id my-replay --max-messages 10000 --output trades.png --no-show
```

Use **`--price-divisor 100`** if prices are fixed-point (e.g. `10000` = 100.00). Run the engine with `KAFKA_BOOTSTRAP_SERVERS` set and generate matches (e.g. `simulate_market.py`).

Alternatives: a small Java main using `SocketChannel`, or PowerShell `[BitConverter]` — possible, but easier to get endianness wrong; this script mirrors the Java test buffers exactly.
