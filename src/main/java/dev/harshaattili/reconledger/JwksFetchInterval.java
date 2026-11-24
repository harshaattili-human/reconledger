package dev.harshaattili.reconledger;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** One process-local attempt budget per decoder, independent of token/key IDs. */
final class JwksFetchInterval {
    private final long intervalNanos;
    private final LongSupplier clock;
    private boolean attempted;
    private long lastAttempt;

    JwksFetchInterval(int millis, LongSupplier clock) {
        if (millis < 0 || millis > 60_000) throw new IllegalArgumentException(
            "recon.security.jwks-min-fetch-interval-millis must be 0..60000");
        this.intervalNanos = TimeUnit.MILLISECONDS.toNanos(millis);
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    synchronized void acquire() throws IOException {
        if (intervalNanos == 0) return;
        long now = clock.getAsLong();
        if (attempted && now - lastAttempt < intervalNanos) {
            throw new IOException("JWKS fetch interval has not elapsed");
        }
        // Failures spend the allowance too. Rejected attempts do not postpone recovery.
        attempted = true;
        lastAttempt = now;
    }
}
