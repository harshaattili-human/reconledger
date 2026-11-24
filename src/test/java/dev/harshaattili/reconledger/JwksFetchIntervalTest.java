package dev.harshaattili.reconledger;

import static org.assertj.core.api.Assertions.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class JwksFetchIntervalTest {
    @Test void exactBoundaryWrapAndRejectedAttemptsDoNotRenewInterval() throws Exception {
        var now = new AtomicLong(Long.MAX_VALUE - 500_000);
        var interval = new JwksFetchInterval(1, now::get);
        interval.acquire();
        now.addAndGet(999_999);
        for (int i = 0; i < 8; i++) assertThatThrownBy(interval::acquire).isInstanceOf(IOException.class);
        now.incrementAndGet();
        interval.acquire();
        assertThatThrownBy(interval::acquire).isInstanceOf(IOException.class);
        var disabled = new JwksFetchInterval(0, now::get);
        for (int i = 0; i < 8; i++) disabled.acquire();
        for (int value : new int[]{-1, 60_001})
            assertThatThrownBy(() -> new JwksFetchInterval(value, now::get))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void simultaneousCallersShareOneAttemptAllowance() throws Exception {
        var interval = new JwksFetchInterval(1000, () -> 0);
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(8);
        try {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 8; i++) results.add(pool.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                try { interval.acquire(); return true; }
                catch (IOException rejected) { return false; }
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int accepted = 0;
            for (var result : results) if (result.get(5, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(1);
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
