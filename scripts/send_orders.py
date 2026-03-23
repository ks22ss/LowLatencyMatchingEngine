#!/usr/bin/env python3
"""
Send binary SUBMIT/CANCEL frames to the matching engine TCP ingress.

Wire layout (big-endian), must match engine.ingress.WireProtocol + OrderFrameDecoder:
  SUBMIT (35 bytes): type(1)=0, orderId(8), side(1) 0=BUY/1=SELL, price(8), qty(8),
                     orderType(1) 0=LIMIT/1=MARKET, timestampNanos(8)
  CANCEL (9 bytes):  type(1)=1, orderId(8)

Examples:
  python send_orders.py match
  python send_orders.py match --host 127.0.0.1 --port 9999
  python send_orders.py burst --pairs 1000
  python send_orders.py submit --order-id 42 --side BUY --price 10000 --qty 1
  python send_orders.py cancel --order-id 42
"""
from __future__ import annotations

import argparse
import socket
import struct
import sys
import time

MSG_SUBMIT = 0
MSG_CANCEL = 1
SIDE_BUY = 0
SIDE_SELL = 1
LIMIT = 0
MARKET = 1

# '>BqBqqBq' = big-endian: u8, i64, u8, i64, i64, u8, i64  -> 35 bytes
SUBMIT_STRUCT = struct.Struct(">BqBqqBq")
CANCEL_STRUCT = struct.Struct(">Bq")


def pack_submit(
    order_id: int,
    side: int,
    price: int,
    quantity: int,
    order_type: int = LIMIT,
    timestamp_nanos: int = 0,
) -> bytes:
    return SUBMIT_STRUCT.pack(
        MSG_SUBMIT, order_id, side, price, quantity, order_type, timestamp_nanos
    )


def pack_cancel(order_id: int) -> bytes:
    return CANCEL_STRUCT.pack(MSG_CANCEL, order_id)


def send_all(sock: socket.socket, data: bytes) -> None:
    sent = 0
    while sent < len(data):
        n = sock.send(data[sent:])
        if n == 0:
            raise ConnectionError("socket closed while sending")
        sent += n


def cmd_match(args: argparse.Namespace) -> None:
    """Resting SELL then crossing BUY at same price (integration-test style)."""
    ts = time.time_ns()
    sell = pack_submit(
        args.sell_id, SIDE_SELL, args.price, args.qty, LIMIT, ts
    )
    buy = pack_submit(
        args.buy_id, SIDE_BUY, args.price, args.qty, LIMIT, ts + 1
    )
    with socket.create_connection((args.host, args.port), timeout=args.timeout) as s:
        send_all(s, sell)
        send_all(s, buy)
    print(f"Sent SELL orderId={args.sell_id} then BUY orderId={args.buy_id} @ price={args.price} qty={args.qty}")


def cmd_submit(args: argparse.Namespace) -> None:
    side = SIDE_BUY if args.side.upper() == "BUY" else SIDE_SELL
    ot = LIMIT if args.order_type.upper() == "LIMIT" else MARKET
    ts = args.timestamp if args.timestamp is not None else time.time_ns()
    frame = pack_submit(args.order_id, side, args.price, args.qty, ot, ts)
    with socket.create_connection((args.host, args.port), timeout=args.timeout) as s:
        send_all(s, frame)
    print(f"Sent SUBMIT orderId={args.order_id} side={args.side} price={args.price} qty={args.qty}")


def cmd_cancel(args: argparse.Namespace) -> None:
    frame = pack_cancel(args.order_id)
    with socket.create_connection((args.host, args.port), timeout=args.timeout) as s:
        send_all(s, frame)
    print(f"Sent CANCEL orderId={args.order_id}")


def cmd_burst(args: argparse.Namespace) -> None:
    """Send N sell/buy pairs (unique order ids) to stress the path."""
    base = args.base_id
    price = args.price
    qty = args.qty
    with socket.create_connection((args.host, args.port), timeout=args.timeout) as s:
        for i in range(args.pairs):
            oid_s = base + 2 * i
            oid_b = base + 2 * i + 1
            ts = time.time_ns()
            send_all(s, pack_submit(oid_s, SIDE_SELL, price, qty, LIMIT, ts))
            send_all(s, pack_submit(oid_b, SIDE_BUY, price, qty, LIMIT, ts + 1))
    print(f"Sent {args.pairs} crossing pairs ({2 * args.pairs} SUBMIT frames) @ price={price}")


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Send orders to LowLatencyMatchingEngine TCP ingress")
    p.add_argument("--host", default="127.0.0.1", help="Engine host (default 127.0.0.1)")
    p.add_argument("--port", type=int, default=9999, help="Engine TCP port (default 9999)")
    p.add_argument("--timeout", type=float, default=5.0, help="Connect/send timeout seconds")

    sub = p.add_subparsers(dest="command", required=True)

    m = sub.add_parser("match", help="Send one SELL then one BUY that cross (default ids 1,2 @ 10000)")
    m.add_argument("--sell-id", type=int, default=1)
    m.add_argument("--buy-id", type=int, default=2)
    m.add_argument("--price", type=int, default=100_00, help="Price in engine units (e.g. 10000 = 100.00 if 2 decimals)")
    m.add_argument("--qty", type=int, default=5)
    m.set_defaults(func=cmd_match)

    s = sub.add_parser("submit", help="Send a single SUBMIT")
    s.add_argument("--order-id", type=int, required=True)
    s.add_argument("--side", choices=("BUY", "SELL"), required=True)
    s.add_argument("--price", type=int, required=True)
    s.add_argument("--qty", type=int, required=True)
    s.add_argument("--order-type", choices=("LIMIT", "MARKET"), default="LIMIT")
    s.add_argument("--timestamp", type=int, default=None, help="timestampNanos (default: now)")
    s.set_defaults(func=cmd_submit)

    c = sub.add_parser("cancel", help="Send a single CANCEL")
    c.add_argument("--order-id", type=int, required=True)
    c.set_defaults(func=cmd_cancel)

    b = sub.add_parser("burst", help="Send many crossing pairs")
    b.add_argument("--pairs", type=int, default=100)
    b.add_argument("--base-id", type=int, default=1, help="First order id (sell); buy is base+1, etc.")
    b.add_argument("--price", type=int, default=100_00)
    b.add_argument("--qty", type=int, default=1)
    b.set_defaults(func=cmd_burst)

    return p


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    try:
        args.func(args)
    except OSError as e:
        print(f"Connection error: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
