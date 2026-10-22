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
import org.springframework.mock.env.MockEnvironment;

class TenantRequestRateBudgetTest {
    @Test void isolatesBurstsAndFailsClosedWhenTrackingIsFull() {
        var clock = new AtomicLong();
        var budget = budget(2, 1000, 2, clock);

        assertThat(budget.acquire("tenant-a")).isZero();
        assertThat(budget.acquire("tenant-a")).isZero();
        assertThat(budget.acquire("tenant-a")).isEqualTo(1);
        assertThat(budget.acquire("tenant-b")).isZero();
        assertThat(budget.acquire("tenant-b")).isZero();
        assertThat(budget.trackedTenants()).isEqualTo(2);

        // Do not evict a depleted bucket: rotating tenant IDs cannot reset a burst.
        assertThat(budget.acquire("tenant-c")).isEqualTo(2);
        assertThat(budget.trackedTenants()).isEqualTo(2);
        clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
        assertThat(budget.acquire("tenant-c")).isZero();
        assertThat(budget.trackedTenants()).isEqualTo(1);
    }

    @Test void concurrentCallersShareOneTenantBurst() throws Exception {
        var budget = budget(3, 1000, 10, new AtomicLong());
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(12);
        var pool = Executors.newFixedThreadPool(12);
        try {
            var results = new ArrayList<Future<Long>>();
            for (int i = 0; i < 12; i++) results.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return budget.acquire("tenant-a");
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var delays = new ArrayList<Long>();
            for (var result : results) delays.add(result.get(5, TimeUnit.SECONDS));
            assertThat(delays.stream().filter(delay -> delay == 0).count()).isEqualTo(3);
            assertThat(delays.stream().filter(delay -> delay == 1).count()).isEqualTo(9);
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test void configurationIsSeparateOptInAndRejectsUnsafeBounds() {
        var context = new ApplicationContextRunner().withUserConfiguration(ApiRateLimit.class)
            .withBean(TenantAccess.class, () -> new TenantAccess(new MockEnvironment()));
        context.withPropertyValues("recon.rate-limit.enabled=true")
            .run(app -> assertThat(app).doesNotHaveBean(TenantRequestRateBudget.class));
        context.withPropertyValues("recon.rate-limit.enabled=true",
                "recon.rate-limit.per-tenant-enabled=true")
            .run(app -> assertThat(app).hasSingleBean(TenantRequestRateBudget.class));
        context.withPropertyValues("recon.rate-limit.enabled=true", "recon.rate-limit.capacity=2",
                "recon.rate-limit.per-tenant-enabled=true", "recon.rate-limit.per-tenant-capacity=3")
            .run(app -> assertThat(app).hasFailed());
        context.withPropertyValues("recon.rate-limit.enabled=true",
                "recon.rate-limit.per-tenant-enabled=true", "recon.rate-limit.max-tracked-tenants=0")
            .run(app -> assertThat(app).hasFailed());
    }

    private static TenantRequestRateBudget budget(int capacity, long refillMillis,
            int maxTrackedTenants, AtomicLong clock) {
        return new TenantRequestRateBudget(capacity, refillMillis, maxTrackedTenants,
            clock::get, new TenantAccess(new MockEnvironment()));
    }
}
