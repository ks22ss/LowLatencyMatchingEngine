package engine.matching;

import engine.domain.Order;
import engine.domain.OrderType;
import engine.domain.Side;
import engine.domain.TradeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderBookTest {

    private OrderBook book;

    @BeforeEach
    void setUp() {
        book = new OrderBook();
    }

    @Nested
    class LimitOrderResting {

        @Test
        void limitBuyRestsWhenNoSellSide() {
            Order buy = Order.of(1L, Side.BUY, 100_00L, 10L, OrderType.LIMIT);
            MatchResult r = book.submit(buy);
            assertTrue(r.trades().isEmpty());
            assertTrue(r.restingOrder().isPresent());
            assertEquals(10L, r.restingOrder().get().quantity());
            assertEquals(100_00L, r.restingOrder().get().price());
        }

        @Test
        void limitSellRestsWhenNoBuySide() {
            Order sell = Order.of(1L, Side.SELL, 100_00L, 10L, OrderType.LIMIT);
            MatchResult r = book.submit(sell);
            assertTrue(r.trades().isEmpty());
            assertTrue(r.restingOrder().isPresent());
            assertEquals(10L, r.restingOrder().get().quantity());
        }

        @Test
        void limitBuyDoesNotCrossWhenAskAbovePrice() {
            book.submit(Order.of(1L, Side.SELL, 101_00L, 5L, OrderType.LIMIT));
            MatchResult r = book.submit(Order.of(2L, Side.BUY, 100_00L, 10L, OrderType.LIMIT));
            assertTrue(r.trades().isEmpty());
            assertTrue(r.restingOrder().isPresent());
            assertEquals(10L, r.restingOrder().get().quantity());
        }
    }

    @Nested
    class LimitOrderMatching {

        @Test
        void limitBuyMatchesAtAskPrice() {
            book.submit(Order.of(1L, Side.SELL, 100_00L, 5L, OrderType.LIMIT));
            MatchResult r = book.submit(Order.of(2L, Side.BUY, 100_00L, 5L, OrderType.LIMIT));
            assertEquals(1, r.trades().size());
            TradeEvent t = r.trades().get(0);
            assertEquals(100_00L, t.price());
            assertEquals(5L, t.quantity());
            assertEquals(1L, t.makerOrderId());
            assertEquals(2L, t.takerOrderId());
            assertTrue(r.restingOrder().isEmpty());
        }

        @Test
        void limitSellMatchesAtBidPrice() {
            book.submit(Order.of(1L, Side.BUY, 100_00L, 5L, OrderType.LIMIT));
            MatchResult r = book.submit(Order.of(2L, Side.SELL, 100_00L, 5L, OrderType.LIMIT));
            assertEquals(1, r.trades().size());
            assertEquals(100_00L, r.trades().get(0).price());
            assertEquals(1L, r.trades().get(0).makerOrderId());
            assertEquals(2L, r.trades().get(0).takerOrderId());
        }

        @Test
        void limitBuyCrossesMultipleLevelsAtRestingPrices() {
            book.submit(Order.of(1L, Side.SELL, 99_00L, 3L, OrderType.LIMIT));
            book.submit(Order.of(2L, Side.SELL, 100_00L, 4L, OrderType.LIMIT));
            MatchResult r = book.submit(Order.of(3L, Side.BUY, 100_00L, 5L, OrderType.LIMIT));
            assertEquals(2, r.trades().size());
            assertEquals(99_00L, r.trades().get(0).price());
            assertEquals(3L, r.trades().get(0).quantity());
            assertEquals(100_00L, r.trades().get(1).price());
            assertEquals(2L, r.trades().get(1).quantity());
            assertTrue(r.restingOrder().isEmpty());
        }

        @Test
        void partialFillIncomingRests() {
            book.submit(Order.of(1L, Side.SELL, 100_00L, 3L, OrderType.LIMIT));
            MatchResult r = book.submit(Order.of(2L, Side.BUY, 100_00L, 10L, OrderType.LIMIT));
            assertEquals(1, r.trades().size());
            assertEquals(3L, r.trades().get(0).quantity());
            assertTrue(r.restingOrder().isPresent());
            assertEquals(7L, r.restingOrder().get().quantity());
        }

        @Test
        void partialFillRestingOrderRemainingInBook() {
            book.submit(Order.of(1L, Side.SELL, 100_00L, 10L, OrderType.LIMIT));
            book.submit(Order.of(2L, Side.BUY, 100_00L, 4L, OrderType.LIMIT));
            MatchResult r = book.submit(Order.of(3L, Side.BUY, 100_00L, 5L, OrderType.LIMIT));
            assertEquals(1, r.trades().size());
            assertEquals(5L, r.trades().get(0).quantity());
            assertTrue(r.restingOrder().isEmpty());
            // Resting sell has 1 left; order 4 buy 10 takes 1, rests 9
            MatchResult r2 = book.submit(Order.of(4L, Side.BUY, 100_00L, 10L, OrderType.LIMIT));
            assertEquals(1, r2.trades().size());
            assertEquals(1L, r2.trades().get(0).quantity());
            assertEquals(1L, r2.trades().get(0).makerOrderId());
            assertTrue(r2.restingOrder().isPresent());
            assertEquals(9L, r2.restingOrder().get().quantity());
        }

        @Test
        void priceTimePrioritySamePriceFifo() {
            book.submit(Order.of(1L, Side.SELL, 100_00L, 2L, OrderType.LIMIT));
            book.submit(Order.of(2L, Side.SELL, 100_00L, 2L, OrderType.LIMIT));
            MatchResult r = book.submit(Order.of(3L, Side.BUY, 100_00L, 3L, OrderType.LIMIT));
            assertEquals(2, r.trades().size());
            assertEquals(1L, r.trades().get(0).makerOrderId());
            assertEquals(2L, r.trades().get(0).quantity());
            assertEquals(2L, r.trades().get(1).makerOrderId());
            assertEquals(1L, r.trades().get(1).quantity());
        }
    }

    @Nested
    class MarketOrder {

        @Test
        void marketBuyMatchesBestAsk() {
            book.submit(Order.of(1L, Side.SELL, 100_00L, 5L, OrderType.LIMIT));
            MatchResult r = book.submit(Order.of(2L, Side.BUY, 0L, 5L, OrderType.MARKET));
            assertEquals(1, r.trades().size());
            assertEquals(100_00L, r.trades().get(0).price());
            assertTrue(r.restingOrder().isEmpty());
        }

        @Test
        void marketBuyWalksBook() {
            book.submit(Order.of(1L, Side.SELL, 99_00L, 2L, OrderType.LIMIT));
            book.submit(Order.of(2L, Side.SELL, 100_00L, 3L, OrderType.LIMIT));
            MatchResult r = book.submit(Order.of(3L, Side.BUY, 0L, 4L, OrderType.MARKET));
            assertEquals(2, r.trades().size());
            assertEquals(99_00L, r.trades().get(0).price());
            assertEquals(2L, r.trades().get(0).quantity());
            assertEquals(100_00L, r.trades().get(1).price());
            assertEquals(2L, r.trades().get(1).quantity());
        }

        @Test
        void marketOrderNoMatchReturnsEmpty() {
            MatchResult r = book.submit(Order.of(1L, Side.BUY, 0L, 10L, OrderType.MARKET));
            assertTrue(r.trades().isEmpty());
            assertTrue(r.restingOrder().isEmpty());
        }
    }

    @Nested
    class Cancel {

        @Test
        void cancelRemovesRestingOrder() {
            book.submit(Order.of(1L, Side.BUY, 100_00L, 10L, OrderType.LIMIT));
            boolean cancelled = book.cancel(1L);
            assertTrue(cancelled);
            MatchResult r = book.submit(Order.of(2L, Side.SELL, 100_00L, 5L, OrderType.LIMIT));
            assertTrue(r.trades().isEmpty());
            assertTrue(r.restingOrder().isPresent());
        }

        @Test
        void cancelUnknownOrderReturnsFalse() {
            boolean cancelled = book.cancel(999L);
            assertFalse(cancelled);
        }

        @Test
        void cancelAlreadyFilledOrderReturnsFalse() {
            book.submit(Order.of(1L, Side.SELL, 100_00L, 5L, OrderType.LIMIT));
            book.submit(Order.of(2L, Side.BUY, 100_00L, 5L, OrderType.LIMIT));
            boolean cancelled = book.cancel(1L);
            assertFalse(cancelled);
        }
    }
}
