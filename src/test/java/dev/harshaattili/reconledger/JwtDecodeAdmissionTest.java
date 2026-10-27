package dev.harshaattili.reconledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

@Timeout(10)
class JwtDecodeAdmissionTest {
    private static Jwt result() {
        return Jwt.withTokenValue("synthetic").header("alg", "RS256").claim("sub", "fixture").build();
    }

    @Test void admitsOnlyThreeOfTwelveSimultaneousCallersWithoutQueueing() throws Exception {
        var ready = new CountDownLatch(12);
        var start = new CountDownLatch(1);
        var entered = new CountDownLatch(3);
        var rejected = new CountDownLatch(9);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var decoder = new JwtDecodeAdmission(token -> {
            calls.incrementAndGet();
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("fixture not released"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            return result();
        }, 3);
        var threads = Executors.newFixedThreadPool(12);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 12; i++) futures.add(threads.submit(() -> {
                ready.countDown();
                start.await();
                try { decoder.decode("synthetic-token"); return true; }
                catch (JwtException unavailable) {
                    assertThat(unavailable).isExactlyInstanceOf(JwtException.class);
                    rejected.countDown();
                    return false;
                }
            }));
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(rejected.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(calls.get()).isEqualTo(3);
            release.countDown();
            int accepted = 0;
            for (var future : futures) if (future.get(2, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(3);
            assertThat(decoder.decode("recovery").getSubject()).isEqualTo("fixture");
        } finally {
            start.countDown(); release.countDown(); threads.shutdownNow();
            assertThat(threads.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void releasesAfterInvalidTokenInfrastructureFailureAndUnexpectedError() {
        var attempts = new AtomicInteger();
        var decoder = new JwtDecodeAdmission(token -> {
            return switch (attempts.getAndIncrement()) {
                case 0 -> throw new BadJwtException("synthetic invalid token");
                case 1 -> throw new JwtException("synthetic unavailable key");
                case 2 -> throw new AssertionError("synthetic unexpected failure");
                default -> result();
            };
        }, 1);
        assertThatThrownBy(() -> decoder.decode("a")).isExactlyInstanceOf(BadJwtException.class);
        assertThatThrownBy(() -> decoder.decode("b")).isExactlyInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode("c")).isInstanceOf(AssertionError.class);
        assertThat(decoder.decode("d").getSubject()).isEqualTo("fixture");
        assertThat(attempts.get()).isEqualTo(4);
    }
}
