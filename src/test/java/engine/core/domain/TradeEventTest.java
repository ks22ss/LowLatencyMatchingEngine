package engine.core.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TradeEventTest {

    @Test
    void validTradeEvent() {
        var t = new TradeEvent(1L, 100_00L, 5L, 10L, 20L, 1234567890L);
        assertEquals(1L, t.tradeId());
        assertEquals(100_00L, t.price());
        assertEquals(5L, t.quantity());
        assertEquals(10L, t.makerOrderId());
        assertEquals(20L, t.takerOrderId());
    }

    @Test
    void rejectsZeroQuantity() {
        assertThrows(IllegalArgumentException.class,
                () -> new TradeEvent(1L, 100L, 0L, 10L, 20L, 0L));
    }

    @Test
    void rejectsNonPositivePrice() {
        assertThrows(IllegalArgumentException.class,
                () -> new TradeEvent(1L, 0L, 5L, 10L, 20L, 0L));
    }
}

