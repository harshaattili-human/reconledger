package dev.harshaattili.reconledger;

import static org.assertj.core.api.Assertions.*;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RequestRateBudgetTest {
    @Test void preservesFractionalRefillAndCapsIdleCredit() {
        var clock = new AtomicLong();
        var budget = new RequestRateBudget(2, 1500, clock::get);
        assertThat(budget.acquire()).isZero();
        assertThat(budget.acquire()).isZero();
        assertThat(budget.acquire()).isEqualTo(2);
        clock.addAndGet(500_000_000L);
        assertThat(budget.acquire()).isEqualTo(1);
        clock.addAndGet(999_999_999L);
        assertThat(budget.acquire()).isEqualTo(1);
        clock.incrementAndGet();
        assertThat(budget.acquire()).isZero();
        assertThat(budget.acquire()).isEqualTo(2);
        clock.addAndGet(TimeUnit.HOURS.toNanos(1));
        assertThat(budget.acquire()).isZero();
        assertThat(budget.acquire()).isZero();
        assertThat(budget.acquire()).isEqualTo(2);
    }

    @Test void concurrentCallersCannotOverspendTheBurst() throws Exception {
        var budget = new RequestRateBudget(3, 1000, () -> 0L);
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(12);
        var pool = Executors.newFixedThreadPool(12);
        try {
            var results = new ArrayList<Future<Long>>();
            for (int i = 0; i < 12; i++) results.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return budget.acquire();
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var delays = new ArrayList<Long>();
            for (var result : results) delays.add(result.get(5, TimeUnit.SECONDS));
            assertThat(delays.stream().filter(delay -> delay == 0).count()).isEqualTo(3);
            assertThat(delays.stream().filter(delay -> delay == 1).count()).isEqualTo(9);
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test void monotonicDifferenceWorksAcrossSignedWrap() {
        var clock = new AtomicLong(Long.MAX_VALUE - 500_000_000L);
        var budget = new RequestRateBudget(1, 1000, clock::get);
        assertThat(budget.acquire()).isZero();
        clock.addAndGet(1_000_000_000L);
        assertThat(budget.acquire()).isZero();
        assertThat(budget.acquire()).isEqualTo(1);
    }

    @Test void rejectsInvalidBoundsAndAcceptsLargestSafeConfiguration() {
        for (int capacity : new int[]{0, -1, 10_001}) {
            assertThatThrownBy(() -> new RequestRateBudget(capacity, 100, () -> 0L))
                .isInstanceOf(IllegalArgumentException.class);
        }
        for (long refill : new long[]{0, -1, 60_001, Long.MAX_VALUE}) {
            assertThatThrownBy(() -> new RequestRateBudget(20, refill, () -> 0L))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new RequestRateBudget(10_000, 60_000, () -> 0L).acquire()).isZero();
    }

    @Test void configurationIsOptInAndInvalidEnabledConfigurationFailsStartup() {
        var context = new ApplicationContextRunner().withUserConfiguration(ApiRateLimit.class);
        context.run(app -> assertThat(app).doesNotHaveBean(RequestRateBudget.class));
        context.withPropertyValues("recon.rate-limit.enabled=true")
            .run(app -> assertThat(app).hasSingleBean(RequestRateBudget.class));
        context.withPropertyValues("recon.rate-limit.enabled=true", "recon.rate-limit.capacity=0")
            .run(app -> assertThat(app).hasFailed());
    }
}
