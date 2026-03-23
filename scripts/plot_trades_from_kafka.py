#!/usr/bin/env python3
"""
Consume fixed 48-byte trade messages from Kafka (same layout as TradeEventBinaryEncoder):
  tradeId, price, quantity, makerOrderId, takerOrderId, timestampNanos  (big-endian int64 each)

Plots execution price vs time (epoch nanos from the message).

Setup:
  pip install -r requirements-kafka-plot.txt

Examples:
  python plot_trades_from_kafka.py --bootstrap localhost:9092 --topic engine-trades --max-messages 500
  python plot_trades_from_kafka.py --from-earliest --max-messages 2000 --output trades.png --no-show
  # True replay from on-disk log (even with a fixed --group-id and committed offsets):
  python plot_trades_from_kafka.py --replay --group-id plot-replay --max-messages 5000 --output trades.png --no-show
  python plot_trades_from_kafka.py --price-divisor 100 --ylabel "Price (units)"
"""
from __future__ import annotations

import argparse
import os
import struct
import sys
import time

TRADE_STRUCT = struct.Struct(">qqqqqq")  # 6 x int64 BE
TRADE_BYTES = TRADE_STRUCT.size  # 48


def decode_trade(payload: bytes) -> tuple[int, int, int, int, int, int]:
    if len(payload) != TRADE_BYTES:
        raise ValueError(f"expected {TRADE_BYTES} bytes, got {len(payload)}")
    return TRADE_STRUCT.unpack(payload)


def main() -> int:
    parser = argparse.ArgumentParser(description="Plot trade prices from Kafka engine-trades stream")
    parser.add_argument("--bootstrap", default="localhost:9092", help="Kafka bootstrap servers")
    parser.add_argument("--topic", default="engine-trades", help="Trades topic name")
    parser.add_argument(
        "--group-id",
        default=None,
        help="Consumer group id (default: plot-trades-<pid>-<time>)",
    )
    parser.add_argument(
        "--from-earliest",
        action="store_true",
        help="New consumers: auto_offset_reset=earliest (no committed offset yet)",
    )
    parser.add_argument(
        "--replay",
        action="store_true",
        help="After partition assignment, seek to beginning of retained log (replay on disk). "
        "Use with a stable --group-id to re-read history; overrides committed offsets for this run.",
    )
    parser.add_argument(
        "--max-messages",
        type=int,
        default=1000,
        help="Stop after this many valid trade messages (default 1000)",
    )
    parser.add_argument(
        "--poll-timeout-ms",
        type=int,
        default=3000,
        help="Consumer poll timeout per batch",
    )
    parser.add_argument(
        "--idle-exit-sec",
        type=float,
        default=15.0,
        help="Exit if no messages for this many seconds (after first message)",
    )
    parser.add_argument(
        "--price-divisor",
        type=float,
        default=1.0,
        help="Divide raw price by this for chart (e.g. 100 for 2-decimal fixed point)",
    )
    parser.add_argument("--output", default="", help="Save figure to this path (png/pdf/svg)")
    parser.add_argument(
        "--no-show",
        action="store_true",
        help="Do not open interactive window (use with --output)",
    )
    parser.add_argument(
        "--max-points-plot",
        type=int,
        default=50_000,
        help="If more trades collected, downsample to this many points for plotting",
    )
    parser.add_argument("--title", default="", help="Chart title (default: auto)")
    parser.add_argument("--ylabel", default="Price", help="Y axis label")
    args = parser.parse_args()

    try:
        from kafka import ConsumerRebalanceListener, KafkaConsumer
    except ImportError:
        print(
            "Missing dependency. Run: pip install -r scripts/requirements-kafka-plot.txt",
            file=sys.stderr,
        )
        return 2

    class _SeekReplayListener(ConsumerRebalanceListener):
        """After each assignment, seek to the oldest retained offset (replay persisted log)."""

        def __init__(self, consumer):
            self._consumer = consumer

        def on_partitions_revoked(self, revoked):
            pass

        def on_partitions_assigned(self, assigned):
            if assigned:
                self._consumer.seek_to_beginning(*assigned)

    try:
        import matplotlib.pyplot as plt
    except ImportError:
        print(
            "Missing matplotlib. Run: pip install -r scripts/requirements-kafka-plot.txt",
            file=sys.stderr,
        )
        return 2

    group_id = args.group_id or f"plot-trades-{os.getpid()}-{time.time_ns()}"
    reset = "earliest" if (args.from_earliest or args.replay) else "latest"

    consumer = KafkaConsumer(
        bootstrap_servers=args.bootstrap.split(","),
        group_id=group_id,
        auto_offset_reset=reset,
        enable_auto_commit=False,
        consumer_timeout_ms=args.poll_timeout_ms,
        value_deserializer=lambda b: b,
    )
    if args.replay:
        consumer.subscribe([args.topic], listener=_SeekReplayListener(consumer))
        mode = "replay (seek_to_beginning after assign)"
    else:
        consumer.subscribe([args.topic])
        mode = f"subscribe (auto_offset_reset={reset})"

    times_s: list[float] = []
    prices: list[float] = []
    errors = 0
    idle_start: float | None = None

    print(
        f"Subscribed to {args.topic} @ {args.bootstrap} ({mode}, group={group_id}, "
        f"need {args.max_messages} trades)..."
    )

    try:
        while len(times_s) < args.max_messages:
            records = consumer.poll(timeout_ms=args.poll_timeout_ms)
            if not records:
                if times_s and idle_start is None:
                    idle_start = time.monotonic()
                if idle_start is not None and (time.monotonic() - idle_start) >= args.idle_exit_sec:
                    print("Idle timeout: no new messages.", file=sys.stderr)
                    break
                continue

            idle_start = None
            for _tp, batch in records.items():
                for record in batch:
                    if record.value is None:
                        continue
                    try:
                        _tid, price, _qty, _maker, _taker, ts_ns = decode_trade(record.value)
                    except ValueError:
                        errors += 1
                        continue
                    # Epoch nanos -> seconds for matplotlib
                    times_s.append(ts_ns / 1e9)
                    prices.append(price / args.price_divisor)
                    if len(times_s) >= args.max_messages:
                        break
                if len(times_s) >= args.max_messages:
                    break
            if len(times_s) >= args.max_messages:
                break
    finally:
        consumer.close()

    if errors:
        print(f"Skipped {errors} malformed records (wrong length).", file=sys.stderr)

    if not times_s:
        print("No trades decoded. Is the engine publishing? Topic empty?", file=sys.stderr)
        return 1

    total_trades = len(times_s)
    # Optional downsample (stride) to keep matplotlib responsive
    if total_trades > args.max_points_plot:
        step = max(1, total_trades // args.max_points_plot)
        times_s = times_s[::step]
        prices = prices[::step]
        print(f"Downsampled with step {step} -> {len(times_s)} points for plotting.")

    t0 = times_s[0]
    x = [t - t0 for t in times_s]

    fig, ax = plt.subplots(figsize=(11, 5), layout="constrained")
    ax.plot(x, prices, color="#1a5f7a", linewidth=0.8, marker="o", markersize=2, alpha=0.85)
    ax.set_xlabel("Time since first trade (s)")
    ax.set_ylabel(args.ylabel)
    title = args.title or f"Trade prices ({args.topic}, n={total_trades})"
    ax.set_title(title)
    ax.grid(True, alpha=0.3)

    if args.output:
        fig.savefig(args.output, dpi=150)
        print(f"Wrote {args.output}")

    if not args.no_show:
        plt.show()
    elif not args.output:
        print("No window (--no-show) and no --output; figure discarded.", file=sys.stderr)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
