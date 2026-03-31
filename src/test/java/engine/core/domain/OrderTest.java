package engine.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderTest {

    @Test
    void limitOrderWithValidFields() {
        var o = Order.of(1L, Side.BUY, 100_00L, 10L, OrderType.LIMIT);
        assertEquals(1L, o.orderId());
        assertEquals(Side.BUY, o.side());
        assertEquals(100_00L, o.price());
        assertEquals(10L, o.quantity());
        assertEquals(OrderType.LIMIT, o.orderType());
        assertEquals(0L, o.timestampNanos());
    }

    @Test
    void marketOrderIgnoresPriceInSemantics() {
        var o = new Order(2L, Side.SELL, 0L, 5L, OrderType.MARKET, 0L);
        assertEquals(OrderType.MARKET, o.orderType());
    }

    @Test
    void rejectsZeroQuantity() {
        assertThrows(IllegalArgumentException.class,
                () -> Order.of(1L, Side.BUY, 100L, 0L, OrderType.LIMIT));
    }

    @Test
    void rejectsNegativeQuantity() {
        assertThrows(IllegalArgumentException.class,
                () -> Order.of(1L, Side.BUY, 100L, -1L, OrderType.LIMIT));
    }

    @Test
    void limitOrderRejectsNonPositivePrice() {
        assertThrows(IllegalArgumentException.class,
                () -> Order.of(1L, Side.BUY, 0L, 10L, OrderType.LIMIT));
    }
}

