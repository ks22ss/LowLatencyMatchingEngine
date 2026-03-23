#!/usr/bin/env python3
"""
Simulate many buyers and sellers competing on one symbol (matches the engine: single order book).

Stochastic model (stdlib random only, no extra packages):
  - Order arrivals: Poisson process - inter-arrival ~ Exponential(rate), mean rate orders/sec.
  - Mid price: mean-reversion (OU-style) toward --anchor-mid plus Gaussian shock (--mid-vol).
  - Each order: BUY with probability --buy-bias, else SELL.
  - Passive limits: bids roughly below mid, asks above; half-normal price jitter creates overlaps
    and crosses. Aggressive fraction posts marketable limits that lift the other side.
  - Size: log-normal-ish exp(N(qty_log_mu, qty_log_sigma)), capped by --qty-max.

Examples:
  python simulate_market.py --duration 30 --rate 200 --seed 42
  python simulate_market.py --orders 50000 --mid-vol 2 --aggressive 0.15 --buy-bias 0.52
"""
from __future__ import annotations

import argparse
import math
import os
import random
import socket
import sys
import time

# Allow `python scripts/simulate_market.py` from repo root as well as `cd scripts`.
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

from send_orders import LIMIT, SIDE_BUY, SIDE_SELL, pack_submit, send_all


def clamp_price(p: int) -> int:
    return max(1, p)


def step_mid_ou(
    mid: float,
    anchor: float,
    reversion: float,
    vol: float,
    rng: random.Random,
) -> float:
    """One step: d(mid) = reversion*(anchor-mid) + N(0, vol). Clamped loosely to stay positive."""
    drift = reversion * (anchor - mid)
    mid = mid + drift + rng.gauss(0.0, vol)
    return max(100.0, mid)


def sample_qty(rng: random.Random, log_mu: float, log_sigma: float, qty_max: int) -> int:
    """Log-normal style: exp(N(log_mu, log_sigma)), at least 1, cap qty_max."""
    x = math.exp(rng.gauss(log_mu, log_sigma))
    q = max(1, min(qty_max, int(round(x))))
    return q


def sample_buy_limit(
    mid: float,
    half_spread: float,
    price_sigma: float,
    aggressive_p: float,
    rng: random.Random,
) -> int:
    if rng.random() < aggressive_p:
        # Marketable buy: pay at or above upper side of the book
        bump = abs(rng.gauss(0.0, price_sigma))
        return clamp_price(int(round(mid + half_spread + bump)))
    # Passive bid: mostly below mid
    back_off = abs(rng.gauss(0.0, price_sigma))
    return clamp_price(int(round(mid - half_spread - back_off)))


def sample_sell_limit(
    mid: float,
    half_spread: float,
    price_sigma: float,
    aggressive_p: float,
    rng: random.Random,
) -> int:
    if rng.random() < aggressive_p:
        bump = abs(rng.gauss(0.0, price_sigma))
        return clamp_price(int(round(mid - half_spread - bump)))
    back_off = abs(rng.gauss(0.0, price_sigma))
    return clamp_price(int(round(mid + half_spread + back_off)))


def cmd_simulate(args: argparse.Namespace) -> None:
    rng = random.Random(args.seed)
    half_spread = max(0.5, args.spread / 2.0)
    mid = float(args.mid)
    anchor = float(args.anchor_mid)

    end_time = time.monotonic() + args.duration if args.duration > 0 else float("inf")
    max_orders = args.orders if args.orders > 0 else None  # None = until duration only

    sent = 0
    next_id = args.base_order_id
    t0 = time.monotonic()

    with socket.create_connection((args.host, args.port), timeout=args.timeout) as sock:
        while time.monotonic() < end_time and (max_orders is None or sent < max_orders):
            mid = step_mid_ou(mid, anchor, args.mean_reversion, args.mid_vol, rng)

            is_buy = rng.random() < args.buy_bias
            if is_buy:
                price = sample_buy_limit(mid, half_spread, args.price_sigma, args.aggressive, rng)
                side = SIDE_BUY
            else:
                price = sample_sell_limit(mid, half_spread, args.price_sigma, args.aggressive, rng)
                side = SIDE_SELL

            qty = sample_qty(rng, args.qty_log_mu, args.qty_log_sigma, args.qty_max)
            ts = time.time_ns()
            send_all(sock, pack_submit(next_id, side, price, qty, LIMIT, ts))
            next_id += 1
            sent += 1

            if args.progress > 0 and sent % args.progress == 0:
                elapsed = time.monotonic() - t0
                print(
                    f"... {sent} orders in {elapsed:.1f}s (~{sent / elapsed:.0f} ord/s), mid~{mid:.1f}",
                    file=sys.stderr,
                )

            if args.rate > 0:
                # Poisson arrivals: inter-arrival ~ Exp(rate); mean gap = 1/rate seconds
                time.sleep(rng.expovariate(args.rate))

    elapsed = time.monotonic() - t0
    print(
        f"Done: {sent} SUBMITs in {elapsed:.2f}s (~{sent / elapsed if elapsed > 0 else 0:.1f} ord/s), "
        f"orderId range [{args.base_order_id}, {next_id - 1}]"
    )


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="Simulate competing buyers/sellers with Poisson arrivals and noisy mid/spreads"
    )
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=9999)
    p.add_argument("--timeout", type=float, default=30.0)

    p.add_argument(
        "--duration",
        type=float,
        default=60.0,
        help="Run this many seconds (use with --rate); 0 = no time limit",
    )
    p.add_argument(
        "--orders",
        type=int,
        default=0,
        help="Stop after this many orders (0 = unlimited until duration ends)",
    )
    p.add_argument(
        "--rate",
        type=float,
        default=100.0,
        help="Average orders/sec (Poisson); 0 = send as fast as possible (busy loop)",
    )

    p.add_argument("--seed", type=int, default=None, help="RNG seed (default: OS entropy)")

    p.add_argument("--mid", type=int, default=100_00, help="Initial mid price (engine ticks)")
    p.add_argument(
        "--anchor-mid",
        type=int,
        default=100_00,
        help="Mean-reversion anchor for mid (same units as --mid)",
    )
    p.add_argument(
        "--mean-reversion",
        type=float,
        default=0.02,
        help="OU strength: each step pulls mid toward anchor (0 = pure random walk drift 0)",
    )
    p.add_argument(
        "--mid-vol",
        type=float,
        default=3.0,
        help="Gaussian shock std dev on mid each order (tick units)",
    )
    p.add_argument(
        "--spread",
        type=float,
        default=20.0,
        help="Nominal spread width; passive bids center ~ mid-half, asks ~ mid+half",
    )
    p.add_argument(
        "--price-sigma",
        type=float,
        default=15.0,
        help="Half-normal scale for extra limit price dispersion (creates crosses)",
    )
    p.add_argument(
        "--aggressive",
        type=float,
        default=0.12,
        help="Probability each order is a marketable limit (crossing side)",
    )
    p.add_argument(
        "--buy-bias",
        type=float,
        default=0.5,
        help="Probability each order is a BUY (0..1); 0.5 = balanced flow",
    )

    p.add_argument(
        "--qty-log-mu",
        type=float,
        default=0.5,
        help="log(size) ~ N(mu, sigma) before round; tweak for typical size",
    )
    p.add_argument("--qty-log-sigma", type=float, default=0.35)
    p.add_argument("--qty-max", type=int, default=500)

    p.add_argument("--base-order-id", type=int, default=1)
    p.add_argument(
        "--progress",
        type=int,
        default=5000,
        help="Print stderr progress every N orders (0 = quiet)",
    )

    p.set_defaults(func=cmd_simulate)
    return p


def main() -> int:
    args = build_parser().parse_args()
    if args.seed is None:
        args.seed = random.randrange(1 << 30)
    if args.duration <= 0 and args.orders <= 0:
        print("Need a stop condition: set --duration > 0 and/or --orders > 0", file=sys.stderr)
        return 2
    if not 0.0 <= args.buy_bias <= 1.0:
        print("--buy-bias must be in [0, 1]", file=sys.stderr)
        return 2
    if not 0.0 <= args.aggressive <= 1.0:
        print("--aggressive must be in [0, 1]", file=sys.stderr)
        return 2
    try:
        cmd_simulate(args)
    except OSError as e:
        print(f"Connection error: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
