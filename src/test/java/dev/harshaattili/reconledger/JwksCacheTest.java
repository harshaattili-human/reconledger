package dev.harshaattili.reconledger;

import static org.assertj.core.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;

class JwksCacheTest {
    private static final String URI = "https://issuer.example.invalid/jwks";

    @Test void expiresAtBoundaryWithoutReadRenewalAndUsesMonotonicElapsedTime() {
        var now = new AtomicLong(Long.MAX_VALUE - TimeUnit.SECONDS.toNanos(1));
        var cache = new JwksCache(URI, 2, now::get);
        cache.put(URI, "first");
        now.addAndGet(TimeUnit.SECONDS.toNanos(2) - 1); // Cross signed long wrap.
        assertThat(cache.get(URI, String.class)).isEqualTo("first");
        assertThat(cache.get(URI).get()).isEqualTo("first");
        now.incrementAndGet();
        assertThat(cache.get(URI)).isNull();
        cache.putIfAbsent(URI, "second");
        assertThat(cache.get(URI, String.class)).isEqualTo("second");
        assertThat(cache.putIfAbsent(URI, "third").get()).isEqualTo("second");
        assertThat(cache.invalidate()).isTrue();
        assertThat(cache.get(URI)).isNull();
    }

    @Test void failedLoadCannotRestoreExpiredValueAndSuccessfulLoadStartsFreshTtl() {
        var now = new AtomicLong();
        var cache = new JwksCache(URI, 1, now::get);
        cache.put(URI, "old");
        now.set(TimeUnit.SECONDS.toNanos(1));
        assertThatThrownBy(() -> cache.get(URI, () -> { throw new IllegalStateException("synthetic"); }))
            .isInstanceOf(Cache.ValueRetrievalException.class);
        assertThat(cache.get(URI)).isNull();
        assertThat(cache.get(URI, () -> {
            now.addAndGet(TimeUnit.SECONDS.toNanos(3));
            return "replacement";
        })).isEqualTo("replacement");
        now.addAndGet(TimeUnit.SECONDS.toNanos(1) - 1);
        assertThat(cache.get(URI, String.class)).isEqualTo("replacement");
        now.incrementAndGet();
        assertThat(cache.get(URI)).isNull();
    }

    @Test void simultaneousMissesLoadOnceAndCannotAllocateOtherEndpointEntries() throws Exception {
        var cache = new JwksCache(URI, 300);
        var calls = new AtomicInteger();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(8);
        try {
            var futures = new java.util.ArrayList<Future<String>>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return cache.get(URI, () -> { calls.incrementAndGet(); return "value"; });
            }));
            start.countDown();
            for (var future : futures) assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("value");
            assertThat(calls.get()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
        assertThatThrownBy(() -> cache.put("other", "value")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cache.get("other")).isInstanceOf(IllegalArgumentException.class);
        assertThat(cache.evictIfPresent(URI)).isTrue();
        assertThat(cache.evictIfPresent(URI)).isFalse();
        assertThatThrownBy(() -> new JwksCache(URI, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JwksCache(URI, 3601)).isInstanceOf(IllegalArgumentException.class);
    }
}
