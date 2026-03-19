package engine.domain;

/**
 * Order side. Used for both resting orders and incoming orders.
 * <p>
 * Enum: one instance per value, no heap allocation when passing sides. JVM can optimize
 * switch/equals to single int comparison. Alternative (byte/int constants) would need
 * validation; enum gives type safety and clear symbol table for serialization.
 */
public enum Side {
    BUY,
    SELL
}
