package main

import (
	"context"
	"encoding/binary"
	"flag"
	"fmt"
	"math/rand"
	"net"
	"os"
	"os/signal"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

// Must match engine.adapters.ingress.WireProtocol + OrderFrameDecoder.
const (
	msgSubmit = byte(0)
	msgCancel = byte(1)

	sideBuy  = byte(0)
	sideSell = byte(1)

	orderTypeLimit  = byte(0)
	orderTypeMarket = byte(1)
)

type cfg struct {
	addr        string
	duration    time.Duration
	conns       int
	rate        float64
	cancelPct   float64
	marketPct   float64
	price       int64
	priceJitter int64
	qtyMin      int64
	qtyMax      int64
	seed        int64
	timeout     time.Duration
}

func main() {
	var c cfg
	flag.StringVar(&c.addr, "addr", "127.0.0.1:9999", "engine TCP ingress address host:port")
	flag.DurationVar(&c.duration, "duration", 60*time.Second, "how long to run")
	flag.IntVar(&c.conns, "conns", 1, "number of concurrent TCP connections (workers)")
	flag.Float64Var(&c.rate, "rate", 0, "target total messages/sec across all conns; 0 = as fast as possible")
	flag.Float64Var(&c.cancelPct, "cancel-pct", 0, "0..100 percent of messages that are CANCEL")
	flag.Float64Var(&c.marketPct, "market-pct", 0, "0..100 percent of SUBMIT messages that are MARKET")
	flag.Int64Var(&c.price, "price", 100_00, "base price (engine units)")
	flag.Int64Var(&c.priceJitter, "price-jitter", 0, "uniform jitter added to price in [-jitter,+jitter]")
	flag.Int64Var(&c.qtyMin, "qty-min", 1, "min quantity")
	flag.Int64Var(&c.qtyMax, "qty-max", 1, "max quantity")
	flag.Int64Var(&c.seed, "seed", 1, "RNG seed (use a fixed seed for repeatability)")
	flag.DurationVar(&c.timeout, "timeout", 3*time.Second, "dial timeout per connection")
	flag.Parse()

	if c.conns < 1 {
		fatalf("conns must be >= 1")
	}
	if c.cancelPct < 0 || c.cancelPct > 100 {
		fatalf("cancel-pct must be 0..100")
	}
	if c.marketPct < 0 || c.marketPct > 100 {
		fatalf("market-pct must be 0..100")
	}
	if c.qtyMin < 1 || c.qtyMax < c.qtyMin {
		fatalf("qty-min must be >=1 and qty-max must be >= qty-min")
	}

	ctx, cancel := context.WithTimeout(context.Background(), c.duration)
	defer cancel()

	ctx = withSignalCancel(ctx, cancel)

	var (
		nextOrderID uint64 = 1
		submits     uint64
		cancels     uint64
		errors      uint64
	)

	start := time.Now()
	var wg sync.WaitGroup
	wg.Add(c.conns)

	// Rate is global; each worker gets an even share.
	perConnRate := c.rate
	if c.rate > 0 {
		perConnRate = c.rate / float64(c.conns)
	}

	// Each worker maintains a small ring of recently submitted order IDs so cancels are usually "valid".
	const cancelRingSize = 2048

	for i := 0; i < c.conns; i++ {
		workerID := i
		// Mix the worker id into the base seed without overflowing int64.
		// (We only need stable per-worker divergence, not cryptographic quality.)
		seed := c.seed ^ int64(uint64(workerID)*11400714819323198485)
		wg.Add(0)
		go func() {
			defer wg.Done()

			rng := rand.New(rand.NewSource(seed))

			var (
				cancelRing [cancelRingSize]uint64
				cancelIdx  uint32
				cancelFill uint32
			)

			dialer := net.Dialer{Timeout: c.timeout}

			var conn net.Conn
			defer func() {
				if conn != nil {
					_ = conn.Close()
				}
			}()

			var buf [35]byte

			limiter := (*time.Ticker)(nil)
			if perConnRate > 0 {
				period := time.Duration(float64(time.Second) / perConnRate)
				if period < time.Microsecond {
					period = time.Microsecond
				}
				limiter = time.NewTicker(period)
				defer limiter.Stop()
			}

			for {
				select {
				case <-ctx.Done():
					return
				default:
				}

				if limiter != nil {
					select {
					case <-limiter.C:
					case <-ctx.Done():
						return
					}
				}

				if conn == nil {
					cn, err := dialer.DialContext(ctx, "tcp", c.addr)
					if err != nil {
						atomic.AddUint64(&errors, 1)
						backoffSleep(ctx, 200*time.Millisecond)
						continue
					}
					conn = cn
				}

				// Decide message type.
				if rng.Float64()*100.0 < c.cancelPct && cancelFill > 0 {
					// CANCEL a recently submitted order id (or a random one if ring not full).
					var oid uint64
					if cancelFill == cancelRingSize {
						oid = cancelRing[rng.Intn(cancelRingSize)]
					} else {
						oid = cancelRing[rng.Intn(int(cancelFill))]
					}
					frame := packCancel(buf[:9], int64(oid))
					if err := writeAll(conn, frame); err != nil {
						atomic.AddUint64(&errors, 1)
						_ = conn.Close()
						conn = nil
						continue
					}
					atomic.AddUint64(&cancels, 1)
					continue
				}

				// SUBMIT
				oid := atomic.AddUint64(&nextOrderID, 1)
				side := sideBuy
				if rng.Intn(2) == 0 {
					side = sideSell
				}

				price := c.price
				if c.priceJitter > 0 {
					j := rng.Int63n(2*c.priceJitter+1) - c.priceJitter
					price += j
					if price < 1 {
						price = 1
					}
				}
				qty := c.qtyMin
				if c.qtyMax > c.qtyMin {
					qty = c.qtyMin + rng.Int63n(c.qtyMax-c.qtyMin+1)
				}

				ot := orderTypeLimit
				if rng.Float64()*100.0 < c.marketPct {
					ot = orderTypeMarket
					// For MARKET, engine ignores price semantics; keep 0 to match tests.
					price = 0
				}

				ts := time.Now().UnixNano()
				frame := packSubmit(buf[:], int64(oid), side, price, qty, ot, ts)
				if err := writeAll(conn, frame); err != nil {
					atomic.AddUint64(&errors, 1)
					_ = conn.Close()
					conn = nil
					continue
				}
				atomic.AddUint64(&submits, 1)

				// Push to cancel ring.
				cancelRing[cancelIdx%cancelRingSize] = oid
				cancelIdx++
				if cancelFill < cancelRingSize {
					cancelFill++
				}
			}
		}()
	}

	wg.Wait()

	elapsed := time.Since(start)
	s := atomic.LoadUint64(&submits)
	k := atomic.LoadUint64(&cancels)
	e := atomic.LoadUint64(&errors)
	total := s + k
	mps := float64(total) / elapsed.Seconds()

	fmt.Printf("Done.\n")
	fmt.Printf("  duration: %s\n", elapsed.Truncate(time.Millisecond))
	fmt.Printf("  conns:    %d\n", c.conns)
	fmt.Printf("  submits:  %d\n", s)
	fmt.Printf("  cancels:  %d\n", k)
	fmt.Printf("  errors:   %d\n", e)
	fmt.Printf("  msg/s:    %.0f\n", mps)
	fmt.Printf("  addr:     %s\n", c.addr)
	fmt.Printf("  started:  %s\n", start.Format(time.RFC3339))
	fmt.Printf("  ended:    %s\n", time.Now().Format(time.RFC3339))
}

func packSubmit(dst []byte, orderID int64, side byte, price int64, qty int64, orderType byte, tsNanos int64) []byte {
	// SUBMIT (35 bytes): type(1)=0, orderId(8), side(1), price(8), qty(8), orderType(1), timestampNanos(8)
	dst[0] = msgSubmit
	binary.BigEndian.PutUint64(dst[1:9], uint64(orderID))
	dst[9] = side
	binary.BigEndian.PutUint64(dst[10:18], uint64(price))
	binary.BigEndian.PutUint64(dst[18:26], uint64(qty))
	dst[26] = orderType
	binary.BigEndian.PutUint64(dst[27:35], uint64(tsNanos))
	return dst[:35]
}

func packCancel(dst []byte, orderID int64) []byte {
	// CANCEL (9 bytes): type(1)=1, orderId(8)
	dst[0] = msgCancel
	binary.BigEndian.PutUint64(dst[1:9], uint64(orderID))
	return dst[:9]
}

func writeAll(conn net.Conn, b []byte) error {
	for len(b) > 0 {
		n, err := conn.Write(b)
		if err != nil {
			return err
		}
		b = b[n:]
	}
	return nil
}

func backoffSleep(ctx context.Context, d time.Duration) {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-t.C:
	case <-ctx.Done():
	}
}

func withSignalCancel(ctx context.Context, cancel func()) context.Context {
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)
	go func() {
		select {
		case <-sigCh:
			cancel()
		case <-ctx.Done():
		}
	}()
	return ctx
}

func fatalf(format string, args ...any) {
	fmt.Fprintf(os.Stderr, format+"\n", args...)
	os.Exit(2)
}

