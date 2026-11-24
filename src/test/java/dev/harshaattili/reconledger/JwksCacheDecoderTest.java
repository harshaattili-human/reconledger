package dev.harshaattili.reconledger;

import static org.assertj.core.api.Assertions.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.*;

class JwksCacheDecoderTest {
    @Test void expiredKnownKeyRefetchesWithoutNewKidAndOutageNeverUsesExpiredKeys() throws Exception {
        var a = key();
        var b = key();
        var body = new AtomicReference<>(jwks("a", a));
        var status = new AtomicInteger(200);
        var fetches = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            fetches.incrementAndGet();
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Cache-Control", "max-age=3600");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            String uri = "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
            var now = new AtomicLong();
            var decoder = NimbusJwtDecoder.withJwkSetUri(uri).restOperations(JwksHttp.create())
                .cache(new JwksCache(uri, 300, now::get)).build();
            String tokenA = token("a", a), tokenB = token("b", b);
            assertThat(decoder.decode(tokenA).getSubject()).isEqualTo("synthetic");
            int warmed = fetches.get();
            body.set(jwks("b", b));
            now.set(TimeUnit.SECONDS.toNanos(300) - 1);
            assertThat(decoder.decode(tokenA).getSubject()).isEqualTo("synthetic");
            assertThat(fetches.get()).isEqualTo(warmed);
            now.incrementAndGet();
            // Same token/kid is submitted first after expiry. No B token triggers refresh.
            assertThatThrownBy(() -> decoder.decode(tokenA)).isInstanceOf(BadJwtException.class);
            assertThat(fetches.get()).isGreaterThan(warmed);
            assertThat(decoder.decode(tokenB).getSubject()).isEqualTo("synthetic");
            int refreshed = fetches.get();
            status.set(503);
            assertThat(decoder.decode(tokenB).getSubject()).isEqualTo("synthetic");
            assertThat(fetches.get()).isEqualTo(refreshed);
            now.addAndGet(TimeUnit.SECONDS.toNanos(300));
            assertThatThrownBy(() -> decoder.decode(tokenB)).isInstanceOf(JwtException.class)
                .isNotInstanceOf(BadJwtException.class);
            assertThat(fetches.get()).isGreaterThan(refreshed);
            status.set(200);
            body.set("malformed synthetic JWKS");
            assertThatThrownBy(() -> decoder.decode(tokenB)).isInstanceOf(JwtException.class);
            body.set(jwks("b", b));
            assertThat(decoder.decode(tokenB).getSubject()).isEqualTo("synthetic");
            // Unknown-kid refresh explicitly invalidates a custom Spring cache. A
            // failed refresh does not preserve even a previously fresh cached key.
            status.set(503);
            assertThatThrownBy(() -> decoder.decode(tokenA)).isInstanceOf(JwtException.class);
            int unknownFailed = fetches.get();
            assertThatThrownBy(() -> decoder.decode(tokenB)).isInstanceOf(JwtException.class);
            assertThat(fetches.get()).isGreaterThan(unknownFailed);
            status.set(200);
            assertThat(decoder.decode(tokenB).getSubject()).isEqualTo("synthetic");
        } finally { server.stop(0); }
    }

    @Test void concurrentExpiredKnownKeyDecodesWaitForOneRefresh() throws Exception {
        var signingKey = key();
        var fetches = new AtomicInteger();
        var refreshEntered = new CountDownLatch(1);
        var releaseRefresh = new CountDownLatch(1);
        var callersReady = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(8);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            int request = fetches.incrementAndGet();
            try {
                if (request > 1) {
                    refreshEntered.countDown();
                    if (!releaseRefresh.await(5, TimeUnit.SECONDS)) return;
                }
                byte[] bytes = jwks("same-key", signingKey).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        server.start();
        try {
            String uri = "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
            var now = new AtomicLong();
            var decoder = NimbusJwtDecoder.withJwkSetUri(uri).restOperations(JwksHttp.create())
                .cache(new JwksCache(uri, 300, now::get)).build();
            String token = token("same-key", signingKey);
            assertThat(decoder.decode(token).getSubject()).isEqualTo("synthetic");
            assertThat(fetches.get()).isEqualTo(1);
            now.set(TimeUnit.SECONDS.toNanos(300));
            var futures = new java.util.ArrayList<Future<String>>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
                callersReady.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return decoder.decode(token).getSubject();
            }));
            assertThat(callersReady.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(refreshEntered.await(5, TimeUnit.SECONDS)).isTrue();
            // The endpoint has received the refresh but cannot return keys yet.
            // No caller may complete from the expired snapshot.
            for (var future : futures) assertThat(future.isDone()).isFalse();
            releaseRefresh.countDown();
            for (var future : futures) assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("synthetic");
            assertThat(fetches.get()).isEqualTo(2);
            assertThat(decoder.decode(token).getSubject()).isEqualTo("synthetic");
            assertThat(fetches.get()).isEqualTo(2);
        } finally {
            start.countDown();
            releaseRefresh.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            server.stop(0);
        }
    }

    @Test void repeatedUnknownKeyRequestsRefetchWhileKnownKeysReuseTheResult() throws Exception {
        var known = key();
        var unknown = key();
        var fetches = new AtomicInteger();
        var body = new AtomicReference<>(jwks("known", known));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            fetches.incrementAndGet();
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            String uri = "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
            var now = new AtomicLong();
            var decoder = new JwtDecodeAdmission(NimbusJwtDecoder.withJwkSetUri(uri)
                .restOperations(JwksHttp.create()).cache(new JwksCache(uri, 300, now::get)).build(), 1);
            String valid = token("known", known);
            String missing = token("missing", unknown);
            assertThat(decoder.decode(valid).getSubject()).isEqualTo("synthetic");
            assertThat(fetches.get()).isEqualTo(1);
            // The clock never advances: these are unknown-kid refreshes, not TTL expiry.
            // Capacity one bounds simultaneous decodes but cannot throttle serial requests.
            for (int i = 0; i < 8; i++) {
                int before = fetches.get();
                assertThatThrownBy(() -> decoder.decode(missing)).isInstanceOf(BadJwtException.class);
                assertThat(fetches.get()).isEqualTo(before + 1);
                assertThat(decoder.decode(valid).getSubject()).isEqualTo("synthetic");
                assertThat(fetches.get()).isEqualTo(before + 1);
            }
            // A newly published key with that previously missing ID remains discoverable.
            body.set(jwks("missing", unknown));
            assertThat(decoder.decode(missing).getSubject()).isEqualTo("synthetic");
            assertThat(fetches.get()).isEqualTo(10);
            assertThat(decoder.decode(missing).getSubject()).isEqualTo("synthetic");
            assertThat(fetches.get()).isEqualTo(10);
        } finally { server.stop(0); }
    }

    @Test void reusedKeyIdRequiresExpiryAndRejectsOldMaterialAfterRefresh() throws Exception {
        var oldKey = key();
        var replacement = key();
        var body = new AtomicReference<>(jwks("stable-id", oldKey));
        var fetches = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            fetches.incrementAndGet();
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            String uri = "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
            var now = new AtomicLong();
            var decoder = NimbusJwtDecoder.withJwkSetUri(uri).restOperations(JwksHttp.create())
                .cache(new JwksCache(uri, 300, now::get)).build();
            String oldToken = token("stable-id", oldKey);
            String newToken = token("stable-id", replacement);
            assertThat(decoder.decode(oldToken).getSubject()).isEqualTo("synthetic");
            assertThat(fetches.get()).isEqualTo(1);
            body.set(jwks("stable-id", replacement));
            now.set(TimeUnit.SECONDS.toNanos(300) - 1);
            for (int i = 0; i < 3; i++) {
                assertThatThrownBy(() -> decoder.decode(newToken)).isInstanceOf(BadJwtException.class);
                assertThat(decoder.decode(oldToken).getSubject()).isEqualTo("synthetic");
                assertThat(fetches.get()).isEqualTo(1);
            }
            now.incrementAndGet();
            assertThat(decoder.decode(newToken).getSubject()).isEqualTo("synthetic");
            assertThat(fetches.get()).isEqualTo(2);
            assertThatThrownBy(() -> decoder.decode(oldToken)).isInstanceOf(BadJwtException.class);
            assertThat(decoder.decode(newToken).getSubject()).isEqualTo("synthetic");
            assertThat(fetches.get()).isEqualTo(2);
        } finally { server.stop(0); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void fetchIntervalBoundsUnknownKeysAndFailuresWithExplicitRecovery(boolean explicitCache) throws Exception {
        var known = key();
        var replacement = key();
        var body = new AtomicReference<>(jwks("known", known));
        var status = new AtomicInteger(200);
        var fetches = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            fetches.incrementAndGet();
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            String uri = "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
            var now = new AtomicLong();
            var builder = NimbusJwtDecoder.withJwkSetUri(uri)
                .restOperations(JwksHttp.create(1000, now::get));
            if (explicitCache) builder.cache(new JwksCache(uri, 300, now::get));
            var decoder = builder.build();
            String valid = token("known", known), rotated = token("new", replacement);
            assertThat(decoder.decode(valid).getSubject()).isEqualTo("synthetic");
            for (int i = 0; i < 8; i++) {
                assertThatThrownBy(() -> decoder.decode(rotated)).isInstanceOf(JwtException.class)
                    .isNotInstanceOf(BadJwtException.class);
            }
            assertThat(fetches.get()).isEqualTo(1);
            // Custom cache is invalidated before a blocked unknown-key refresh.
            if (explicitCache) assertThatThrownBy(() -> decoder.decode(valid))
                .isInstanceOf(JwtException.class).isNotInstanceOf(BadJwtException.class);
            else assertThat(decoder.decode(valid).getSubject()).isEqualTo("synthetic");
            body.set(jwks("new", replacement));
            now.set(TimeUnit.SECONDS.toNanos(1) - 1);
            assertThatThrownBy(() -> decoder.decode(rotated)).isInstanceOf(JwtException.class);
            now.incrementAndGet();
            assertThat(decoder.decode(rotated).getSubject()).isEqualTo("synthetic");
            assertThat(fetches.get()).isEqualTo(2);
            assertThat(decoder.decode(rotated).getSubject()).isEqualTo("synthetic");
            now.addAndGet(TimeUnit.SECONDS.toNanos(1));
            status.set(503);
            assertThatThrownBy(() -> decoder.decode(valid)).isInstanceOf(JwtException.class);
            assertThat(fetches.get()).isEqualTo(3);
            status.set(200);
            body.set(jwks("known", known));
            for (int i = 0; i < 8; i++)
                assertThatThrownBy(() -> decoder.decode(valid)).isInstanceOf(JwtException.class);
            assertThat(fetches.get()).isEqualTo(3); // Failed attempts spend the interval too.
            now.addAndGet(TimeUnit.SECONDS.toNanos(1));
            assertThat(decoder.decode(valid).getSubject()).isEqualTo("synthetic");
            assertThat(fetches.get()).isEqualTo(4);
        } finally { server.stop(0); }
    }

    private static KeyPair key() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
    private static String jwks(String kid, KeyPair pair) {
        return "{\"keys\":[" + new RSAKey.Builder((RSAPublicKey) pair.getPublic())
            .keyID(kid).algorithm(JWSAlgorithm.RS256).build().toJSONString() + "]}";
    }
    private static String token(String kid, KeyPair pair) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(),
            new JWTClaimsSet.Builder().subject("synthetic")
                .expirationTime(Date.from(Instant.now().plusSeconds(900))).build());
        jwt.sign(new RSASSASigner(pair.getPrivate()));
        return jwt.serialize();
    }
}
