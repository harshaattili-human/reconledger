package dev.harshaattili.reconledger;

import java.util.function.LongSupplier;

/** One process-wide token bucket; no per-caller state or background refill thread. */
final class RequestRateBudget {
    private final long capacityNanos;
    private final long tokenNanos;
    private final LongSupplier clock;
    private long availableNanos;
    private long previousNanos;

    RequestRateBudget(int capacity, long refillMillis, LongSupplier clock) {
        if (capacity < 1 || capacity > 10_000 || refillMillis < 1 || refillMillis > 60_000) {
            throw new IllegalArgumentException("Rate capacity must be 1..10000 and refill-millis 1..60000");
        }
        this.tokenNanos = refillMillis * 1_000_000L;
        this.capacityNanos = capacity * tokenNanos;
        this.availableNanos = capacityNanos;
        this.clock = clock;
        this.previousNanos = clock.getAsLong();
    }

    /** Zero admits one request; otherwise return a whole-second retry delay. */
    synchronized long acquire() {
        refill();
        if (availableNanos >= tokenNanos) {
            availableNanos -= tokenNanos;
            return 0;
        }
        return (tokenNanos - availableNanos + 999_999_999L) / 1_000_000_000L;
    }

    /** Used to discard idle tenant buckets only after their entire burst has returned. */
    synchronized long secondsUntilFull() {
        refill();
        return (capacityNanos - availableNanos + 999_999_999L) / 1_000_000_000L;
    }

    private void refill() {
        long now = clock.getAsLong();
        // nanoTime differences remain valid across signed wrap (interval < 292 years).
        long elapsed = now - previousNanos;
        if (elapsed > 0) {
            availableNanos += Math.min(elapsed, capacityNanos - availableNanos);
            previousNanos = now;
        }
    }
}
