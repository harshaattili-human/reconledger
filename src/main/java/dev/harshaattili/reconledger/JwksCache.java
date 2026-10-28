package dev.harshaattili.reconledger;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.util.Assert;

/** One configured JWKS endpoint, expire-after-successful-load; no stale fallback. */
final class JwksCache implements Cache {
    private final String uri;
    private final long ttlNanos;
    private final LongSupplier clock;
    private String value;
    private long loadedAt;

    JwksCache(String uri, int ttlSeconds) { this(uri, ttlSeconds, System::nanoTime); }

    JwksCache(String uri, int ttlSeconds, LongSupplier clock) {
        Assert.isTrue(ttlSeconds >= 1 && ttlSeconds <= 3600,
            "recon.security.jwks-cache-ttl-seconds must be 0 (framework default) or 1..3600");
        this.uri = uri;
        this.ttlNanos = TimeUnit.SECONDS.toNanos(ttlSeconds);
        this.clock = clock;
    }

    @Override public String getName() { return "jwks"; }
    @Override public Object getNativeCache() { return this; }

    private void key(Object key) {
        Assert.isTrue(uri.equals(key), "JWKS cache accepts only its configured endpoint");
    }

    private String current(Object key) {
        key(key);
        if (value != null && clock.getAsLong() - loadedAt >= ttlNanos) value = null;
        return value;
    }

    @Override public synchronized ValueWrapper get(Object key) {
        String found = current(key);
        return found == null ? null : new SimpleValueWrapper(found);
    }

    @Override public synchronized <T> T get(Object key, Class<T> type) {
        String found = current(key);
        if (found == null) return null;
        if (type != null && !type.isInstance(found)) throw new IllegalStateException("JWKS cache value type mismatch");
        @SuppressWarnings("unchecked") T result = (T) found;
        return result;
    }

    @Override public synchronized <T> T get(Object key, Callable<T> loader) {
        String found = current(key);
        if (found != null) {
            @SuppressWarnings("unchecked") T result = (T) found;
            return result;
        }
        try {
            T loaded = loader.call();
            put(key, loaded); // Timestamp after retrieval and parse, never on a read.
            return loaded;
        } catch (Exception failure) {
            throw new ValueRetrievalException(key, loader, failure);
        }
    }

    @Override public synchronized void put(Object key, Object candidate) {
        key(key);
        Assert.isTrue(candidate instanceof String, "JWKS cache requires a JSON string");
        value = (String) candidate;
        loadedAt = clock.getAsLong();
    }

    @Override public synchronized ValueWrapper putIfAbsent(Object key, Object candidate) {
        ValueWrapper found = get(key);
        if (found == null) put(key, candidate);
        return found;
    }

    @Override public synchronized void evict(Object key) { key(key); value = null; }
    @Override public synchronized boolean evictIfPresent(Object key) {
        boolean present = current(key) != null;
        value = null;
        return present;
    }
    @Override public synchronized void clear() { value = null; }
    @Override public synchronized boolean invalidate() {
        boolean present = value != null;
        value = null;
        return present;
    }
}
