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
	"strings"
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

	maxFrameBytes = 35 // SUBMIT
)

type workloadKind int

const (
	modeDefault workloadKind = iota
	modeCrossingPairs
	modeRestHeavy
	modeCancelHeavy
	modeMarketHeavy
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
	batchFrames int
	restSpread  int64
	kind        workloadKind
	modeLabel   string
}

func main() {
	var c cfg
	var modeFlag string
	flag.StringVar(&c.addr, "addr", "127.0.0.1:9999", "engine TCP ingress address host:port")
	flag.DurationVar(&c.duration, "duration", 60*time.Second, "how long to run")
	flag.IntVar(&c.conns, "conns", 1, "number of concurrent TCP connections (workers)")
	flag.Float64Var(&c.rate, "rate", 0, "target total messages/sec across all conns; 0 = as fast as possible")
	flag.Float64Var(&c.cancelPct, "cancel-pct", 0, "0..100 CANCEL share when -mode is empty; -mode presets replace this")
	flag.Float64Var(&c.marketPct, "market-pct", 0, "0..100 MARKET share when -mode is empty; -mode presets replace this")
	flag.Int64Var(&c.price, "price", 100_00, "base price (engine units); mid for rest-heavy")
	flag.Int64Var(&c.priceJitter, "price-jitter", 0, "uniform jitter added to price in [-jitter,+jitter] (crossing/default)")
	flag.Int64Var(&c.qtyMin, "qty-min", 1, "min quantity")
	flag.Int64Var(&c.qtyMax, "qty-max", 1, "max quantity")
	flag.Int64Var(&c.seed, "seed", 1, "RNG seed (use a fixed seed for repeatability)")
	flag.DurationVar(&c.timeout, "timeout", 3*time.Second, "dial timeout per connection")
	flag.IntVar(&c.batchFrames, "batch", 128, "max frames to buffer per Write (64–256 typical)")
	flag.Int64Var(&c.restSpread, "rest-spread", 0, "bid=price-spread ask=price+spread for rest-heavy; default 2000 when mode=rest")
	flag.StringVar(&modeFlag, "mode", "",
		"workload preset: crossing (paired BUY/SELL limits at same price), rest (wide spread, resting book), cancel (high cancels), market (high market orders); empty = random/jitter from flags")
	flag.Parse()

	if err := c.applyMode(modeFlag); err != nil {
		fatalf("%v", err)
	}

	if c.conns < 1 {
		fatalf("conns must be >= 1")
	}
	if c.batchFrames < 1 {
		fatalf("batch must be >= 1")
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
	if c.kind == modeRestHeavy {
		if c.price-c.restSpread < 1 {
			fatalf("rest-heavy: price - rest-spread must be >= 1 (price=%d rest-spread=%d)", c.price, c.restSpread)
		}
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

	if c.modeLabel != "" {
		fmt.Printf("loadgen mode: %s (cancel-pct=%.0f market-pct=%.0f", c.modeLabel, c.cancelPct, c.marketPct)
		if c.kind == modeRestHeavy {
			fmt.Printf(" rest-spread=%d", c.restSpread)
		}
		fmt.Printf(")\n")
	}

	go progressReporter(ctx, start, &submits, &cancels, &errors)

	var wg sync.WaitGroup
	wg.Add(c.conns)

	perConnRate := c.rate
	if c.rate > 0 {
		perConnRate = c.rate / float64(c.conns)
	}

	const cancelRingSize = 2048

	for i := 0; i < c.conns; i++ {
		workerID := i
		seed := c.seed ^ int64(uint64(workerID)*11400714819323198485)
		go func() {
			defer wg.Done()

			var scratch [maxFrameBytes]byte
			batchCap := c.batchFrames * maxFrameBytes
			batch := make([]byte, 0, batchCap)
			queuedFrames := 0

			var conn net.Conn

			flush := func() error {
				if len(batch) == 0 {
					return nil
				}
				if conn == nil {
					return nil
				}
				err := writeAll(conn, batch)
				batch = batch[:0]
				queuedFrames = 0
				return err
			}

			rng := rand.New(rand.NewSource(seed))

			var (
				cancelRing [cancelRingSize]uint64
				cancelIdx  uint32
				cancelFill uint32
			)

			// Crossing pairs: alternate BUY/SELL at the same peg price so each aggressive leg hits the resting opposing limit.
			nextCrossIsBuy := workerID%2 == 0

			dialer := net.Dialer{Timeout: c.timeout}
			defer func() {
				_ = flush()
				if conn != nil {
					_ = conn.Close()
				}
			}()

			limiter := (*time.Ticker)(nil)
			if perConnRate > 0 {
				period := time.Duration(float64(time.Second) / perConnRate)
				if period < time.Microsecond {
					period = time.Microsecond
				}
				limiter = time.NewTicker(period)
				defer limiter.Stop()
			}

		workLoop:
			for {
				select {
				case <-ctx.Done():
					break workLoop
				default:
				}

				if limiter != nil {
					select {
					case <-limiter.C:
					case <-ctx.Done():
						break workLoop
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

				if rng.Float64()*100.0 < c.cancelPct && cancelFill > 0 {
					var oid uint64
					if cancelFill == cancelRingSize {
						oid = cancelRing[rng.Intn(cancelRingSize)]
					} else {
						oid = cancelRing[rng.Intn(int(cancelFill))]
					}
					frame := packCancel(scratch[:9], int64(oid))
					batch = append(batch, frame...)
					queuedFrames++
					atomic.AddUint64(&cancels, 1)

				} else {
					oid := atomic.AddUint64(&nextOrderID, 1)
					var side byte
					var price int64
					var ot byte

					switch c.kind {
					case modeCrossingPairs:
						side = sideSell
						if nextCrossIsBuy {
							side = sideBuy
						}
						nextCrossIsBuy = !nextCrossIsBuy
						price = c.price
						if c.priceJitter > 0 {
							j := rng.Int63n(2*c.priceJitter+1) - c.priceJitter
							price += j
							if price < 1 {
								price = 1
							}
						}
						ot = orderTypeLimit

					case modeRestHeavy:
						if rng.Intn(2) == 0 {
							side = sideBuy
							price = c.price - c.restSpread
						} else {
							side = sideSell
							price = c.price + c.restSpread
						}
						if price < 1 {
							price = 1
						}
						ot = orderTypeLimit

					default:
						side = sideBuy
						if rng.Intn(2) == 0 {
							side = sideSell
						}
						price = c.price
						if c.priceJitter > 0 {
							j := rng.Int63n(2*c.priceJitter+1) - c.priceJitter
							price += j
							if price < 1 {
								price = 1
							}
						}
						ot = orderTypeLimit
						if rng.Float64()*100.0 < c.marketPct {
							ot = orderTypeMarket
							price = 0
						}
					}

					qty := c.qtyMin
					if c.qtyMax > c.qtyMin {
						qty = c.qtyMin + rng.Int63n(c.qtyMax-c.qtyMin+1)
					}

					ts := time.Now().UnixNano()
					frame := packSubmit(scratch[:], int64(oid), side, price, qty, ot, ts)
					batch = append(batch, frame...)
					queuedFrames++
					atomic.AddUint64(&submits, 1)

					cancelRing[cancelIdx%cancelRingSize] = oid
					cancelIdx++
					if cancelFill < cancelRingSize {
						cancelFill++
					}
				}

				if queuedFrames >= c.batchFrames {
					if err := flush(); err != nil {
						atomic.AddUint64(&errors, 1)
						_ = conn.Close()
						conn = nil
					}
				}
			}

			if err := flush(); err != nil {
				atomic.AddUint64(&errors, 1)
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
	if c.modeLabel != "" {
		fmt.Printf("  mode:     %s\n", c.modeLabel)
	}
	fmt.Printf("  conns:    %d\n", c.conns)
	fmt.Printf("  batch:    %d frames/write\n", c.batchFrames)
	fmt.Printf("  submits:  %d\n", s)
	fmt.Printf("  cancels:  %d\n", k)
	fmt.Printf("  errors:   %d\n", e)
	fmt.Printf("  msg/s:    %.0f\n", mps)
	fmt.Printf("  addr:     %s\n", c.addr)
	fmt.Printf("  started:  %s\n", start.Format(time.RFC3339))
	fmt.Printf("  ended:    %s\n", time.Now().Format(time.RFC3339))
}

func (c *cfg) applyMode(s string) error {
	s = strings.TrimSpace(strings.ToLower(s))
	if s == "" || s == "default" || s == "random" {
		c.kind = modeDefault
		c.modeLabel = ""
		return nil
	}

	switch s {
	case "crossing", "crossing-pairs", "cross":
		c.kind = modeCrossingPairs
		c.modeLabel = "crossing"
		c.cancelPct = 5
		c.marketPct = 0
	case "rest", "rest-heavy":
		c.kind = modeRestHeavy
		c.modeLabel = "rest-heavy"
		c.cancelPct = 3
		c.marketPct = 0
		if c.restSpread == 0 {
			c.restSpread = 2000
		}
	case "cancel", "cancel-heavy":
		c.kind = modeCancelHeavy
		c.modeLabel = "cancel-heavy"
		c.cancelPct = 45
		c.marketPct = 0
	case "market", "market-heavy":
		c.kind = modeMarketHeavy
		c.modeLabel = "market-heavy"
		c.cancelPct = 5
		c.marketPct = 50
	default:
		return fmt.Errorf("unknown -mode %q (try crossing, rest-heavy, cancel-heavy, market-heavy)", s)
	}
	return nil
}

func progressReporter(ctx context.Context, start time.Time, submits, cancels, errors *uint64) {
	tick := time.NewTicker(5 * time.Second)
	defer tick.Stop()

	var prevTotal uint64
	prevAt := start

	for {
		select {
		case <-ctx.Done():
			return
		case t := <-tick.C:
			total := atomic.LoadUint64(submits) + atomic.LoadUint64(cancels)
			dt := t.Sub(prevAt).Seconds()
			if dt > 0 {
				fmt.Printf("[progress] interval_msg/s=%.0f  total_msgs=%d  errors=%d\n",
					float64(total-prevTotal)/dt,
					total,
					atomic.LoadUint64(errors))
			}
			prevTotal = total
			prevAt = t
		}
	}
}

func packSubmit(dst []byte, orderID int64, side byte, price int64, qty int64, orderType byte, tsNanos int64) []byte {
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
