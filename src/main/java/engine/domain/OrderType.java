package engine.domain;

/**
 * Order type. LIMIT rests in the book at a price; MARKET matches only (no resting).
 * <p>
 * Kept explicit (no IOC/FOK) so matching has a single, predictable path per type and
 * we avoid branching on time-in-force in the hot path. Enum again avoids allocation
 * and gives a stable ordinal for wire/serialization if needed.
 */
public enum OrderType {
    LIMIT,
    MARKET
}
