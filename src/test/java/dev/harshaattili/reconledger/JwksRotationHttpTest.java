package dev.harshaattili.reconledger;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "secured"})
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class JwksRotationHttpTest {
    private static final String ISSUER = "https://issuer.example.invalid/jwks-test";
    private static final String AUDIENCE = "reconledger-jwks-test";
    private static final KeyPair KEY_A = keyPair();
    private static final KeyPair KEY_B = keyPair();
    private static final AtomicReference<String> JWKS = new AtomicReference<>(jwks("key-a", KEY_A));
    private static final AtomicInteger STATUS = new AtomicInteger(200);
    private static final AtomicInteger FETCHES = new AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicBoolean SLOW = new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.ExecutorService ENDPOINT_THREADS = java.util.concurrent.Executors.newCachedThreadPool();
    private static final AtomicReference<java.util.concurrent.CountDownLatch> HOLD = new AtomicReference<>();
    private static final AtomicReference<java.util.concurrent.CountDownLatch> ENTERED = new AtomicReference<>();
    private static final HttpServer SERVER = server();
    private static final String JWK_SET_URI = "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/jwks";
    private static final String BATCH = """
        {"businessDate":"2026-10-26","currency":"USD",
         "leftRecords":[{"recordId":"left-1","reference":"INV-1","amount":"10.00"}],
         "rightRecords":[]}
        """;

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("recon.security.max-concurrent-decodes", () -> "1");
        registry.add("recon.security.jwk-set-uri", () -> JWK_SET_URI);
        registry.add("recon.security.issuer", () -> ISSUER);
        registry.add("recon.security.audience", () -> AUDIENCE);
    }

    @BeforeEach void resetEndpoint() {
        JWKS.set(jwks("key-a", KEY_A));
        STATUS.set(200);
        FETCHES.set(0);
        SLOW.set(false);
        HOLD.set(null);
        ENTERED.set(null);
    }

    @AfterAll static void stopServer() { SERVER.stop(0); ENDPOINT_THREADS.shutdownNow(); }

    @Test void refreshesUnknownKeyIdsWithoutWeakeningClaimsScopesOrTenantIsolation() throws Exception {
        String keyA = token(KEY_A, "key-a", "tenant-a", "writer-a", "recon:write", JWSAlgorithm.RS256);
        var first = post(BATCH, keyA, idempotency());
        assertThat(first.statusCode()).isEqualTo(201);
        String firstId = body(first).get("id").asText();
        int initialFetches = FETCHES.get();
        assertThat(initialFetches).isGreaterThanOrEqualTo(1);

        JWKS.set(jwks("key-b", KEY_B));
        String keyB = token(KEY_B, "key-b", "tenant-b", "writer-b", "recon:read recon:write",
            JWSAlgorithm.RS256);
        var rotated = post(BATCH.replace("10.00", "11.00"), keyB, idempotency());
        assertThat(rotated.statusCode()).isEqualTo(201);
        assertThat(FETCHES.get()).isGreaterThan(initialFetches);

        assertThat(get("/api/batches/" + firstId, keyB).statusCode()).isEqualTo(404);
        assertThat(get("/api/batches/" + firstId,
            token(KEY_B, "key-b", "tenant-a", "reader-a", "recon:read", JWSAlgorithm.RS256)).statusCode())
            .isEqualTo(200);

        String rejectedKey = idempotency();
        assertThat(post(BATCH, keyA, rejectedKey).statusCode()).isEqualTo(401);
        assertThat(post(BATCH, token(KEY_B, "key-b", "tenant-b", "writer-b", "recon:write",
            JWSAlgorithm.RS512), rejectedKey).statusCode()).isEqualTo(401);
        assertThat(post(BATCH, token(KEY_B, "key-b", "demo", "writer-b", "recon:write",
            JWSAlgorithm.RS256), rejectedKey).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recon_batch WHERE idempotency_key = ?",
            Integer.class, rejectedKey)).isZero();

        assertThat(post(BATCH, keyB, rejectedKey).statusCode()).isEqualTo(201);
        assertThat(get("/api/batches", token(KEY_B, "key-b", "tenant-b", "no-scope", "",
            JWSAlgorithm.RS256)).statusCode()).isEqualTo(403);
    }

    @Test void unavailableEndpointFailsClosedAndCachedKeySurvivesFailedRefresh(CapturedOutput output) throws Exception {
        String keyA = token(KEY_A, "key-a", "outage-a", "writer-a", "recon:read recon:write",
            JWSAlgorithm.RS256);
        String rejectedKey = idempotency();
        STATUS.set(503);
        assertUnavailable(post(BATCH, keyA, rejectedKey));
        assertNoBatch(rejectedKey);
        assertThat(FETCHES.get()).isPositive();

        STATUS.set(200);
        var created = post(BATCH, keyA, rejectedKey);
        assertThat(created.statusCode()).isEqualTo(201);
        String batchId = body(created).get("id").asText();
        int warmed = FETCHES.get();
        STATUS.set(503);
        assertThat(get("/api/batches/" + batchId, keyA).statusCode()).isEqualTo(200);
        assertThat(FETCHES.get()).isEqualTo(warmed); // Cached key; no network required yet.

        String keyB = token(KEY_B, "key-b", "outage-b", "writer-b", "recon:read recon:write",
            JWSAlgorithm.RS256);
        String pending = idempotency();
        assertUnavailable(post(BATCH, keyB, pending));
        assertNoBatch(pending);
        assertThat(FETCHES.get()).isGreaterThan(warmed);
        int failedRefresh = FETCHES.get();
        assertThat(get("/api/batches/" + batchId, keyA).statusCode()).isEqualTo(200);
        assertThat(FETCHES.get()).isEqualTo(failedRefresh);

        STATUS.set(200);
        JWKS.set(jwks("key-b", KEY_B));
        assertThat(post(BATCH, keyB, pending).statusCode()).isEqualTo(201);
        assertThat(get("/api/batches/" + batchId, keyB).statusCode()).isEqualTo(404);
        assertThat(get("/api/batches/" + batchId, keyA).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recon_batch WHERE idempotency_key = ?",
            Integer.class, pending)).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("synthetic-jwks-body-marker", "Servlet.service()");
    }

    @Test void malformedAndEmptyKeySetsRejectUnknownKeysThenRecoverWithoutRestart(CapturedOutput output) throws Exception {
        String keyA = token(KEY_A, "key-a", "invalid-set", "writer", "recon:read recon:write",
            JWSAlgorithm.RS256);
        assertThat(post(BATCH, keyA, idempotency()).statusCode()).isEqualTo(201);
        String keyB = token(KEY_B, "key-b", "invalid-set", "writer", "recon:read recon:write",
            JWSAlgorithm.RS256);
        String rejected = idempotency();
        JWKS.set("{synthetic-jwks-body-marker");
        int before = FETCHES.get();
        assertUnavailable(post(BATCH, keyB, rejected));
        assertThat(FETCHES.get()).isGreaterThan(before);
        assertNoBatch(rejected);

        JWKS.set("{\"keys\":[]}");
        assertThat(post(BATCH, keyB, rejected).statusCode()).isEqualTo(401);
        assertThat(post(BATCH, keyA, rejected).statusCode()).isEqualTo(401);
        assertNoBatch(rejected);

        JWKS.set(jwks("key-b", KEY_B));
        assertThat(post(BATCH, keyB, rejected).statusCode()).isEqualTo(201);
        assertThat(post(BATCH, keyB, rejected).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recon_batch WHERE idempotency_key = ?",
            Integer.class, rejected)).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("synthetic-jwks-body-marker", "Servlet.service()");
    }

    @Test void oversizedKeySetCannotWriteAndValidReplacementRecovers(CapturedOutput output) throws Exception {
        String keyA = token(KEY_A, "key-a", "bounded-set", "writer", "recon:read recon:write",
            JWSAlgorithm.RS256);
        String pending = idempotency();
        String valid = jwks("key-a", KEY_A);
        JWKS.set(valid + " ".repeat(JwksHttp.MAX_BYTES + 1 - valid.length()));
        assertUnavailable(post(BATCH, keyA, pending));
        assertNoBatch(pending);
        JWKS.set(valid);
        assertThat(post(BATCH, keyA, pending).statusCode()).isEqualTo(201);
        assertThat(post(BATCH, keyA, pending).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recon_batch WHERE idempotency_key = ?",
            Integer.class, pending)).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("Servlet.service()");
    }

    @Test void tricklingKeySetTimesOutWithoutWritingAndThenRecovers(CapturedOutput output) throws Exception {
        String keyA = token(KEY_A, "key-a", "slow-set", "writer", "recon:read recon:write",
            JWSAlgorithm.RS256);
        String pending = idempotency();
        SLOW.set(true);
        try {
            assertUnavailable(post(BATCH, keyA, pending));
            assertNoBatch(pending);
        } finally { SLOW.set(false); }
        assertThat(post(BATCH, keyA, pending).statusCode()).isEqualTo(201);
        assertThat(post(BATCH, keyA, pending).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recon_batch WHERE idempotency_key = ?",
            Integer.class, pending)).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("Servlet.service()", JWK_SET_URI);
    }

    @Test void saturationRejectsEvenCachedTokensBeforeDecoderWorkAndRecovers(CapturedOutput output) throws Exception {
        String keyA = token(KEY_A, "key-a", "admission-a", "writer-a", "recon:read recon:write",
            JWSAlgorithm.RS256);
        String keyB = token(KEY_B, "key-b", "admission-b", "writer-b", "recon:read recon:write",
            JWSAlgorithm.RS256);
        assertThat(post(BATCH, keyA, idempotency()).statusCode()).isEqualTo(201); // Warm A.
        JWKS.set(jwks("key-b", KEY_B));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        ENTERED.set(entered);
        HOLD.set(release);
        String firstKey = idempotency();
        String rejectedKey = idempotency();
        var first = client.sendAsync(HttpRequest.newBuilder(uri("/api/batches"))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + keyB).header("Idempotency-Key", firstKey)
            .POST(HttpRequest.BodyPublishers.ofString(BATCH)).build(), HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            int fetches = FETCHES.get();
            assertUnavailable(post(BATCH, keyA, rejectedKey)); // A is cached, but admission precedes Nimbus.
            assertThat(first.isDone()).isFalse();
            assertThat(FETCHES.get()).isEqualTo(fetches);
            assertNoBatch(firstKey);
            assertNoBatch(rejectedKey);
            assertThat(client.send(HttpRequest.newBuilder(uri("/actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            assertThat(client.send(HttpRequest.newBuilder(uri("/api/batches")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        } finally { HOLD.set(null); ENTERED.set(null); release.countDown(); }
        assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(201);
        assertThat(post(BATCH, keyB, rejectedKey).statusCode()).isEqualTo(201);
        assertThat(post(BATCH, keyB, rejectedKey).statusCode()).isEqualTo(200);
        assertThat(get("/api/batches", "synthetic-invalid-token").statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recon_batch WHERE idempotency_key = ?",
            Integer.class, rejectedKey)).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("Token verification capacity unavailable", "Servlet.service()");
    }

    private void assertUnavailable(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(body(response).get("title").asText()).isEqualTo("Token verification unavailable");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
        assertThat(response.headers().firstValue("Retry-After")).isEmpty();
        assertThat(response.body()).doesNotContain("synthetic-jwks-body-marker", JWK_SET_URI);
    }

    private void assertNoBatch(String key) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recon_batch WHERE idempotency_key = ?",
            Integer.class, key)).isZero();
    }

    private HttpResponse<String> post(String body, String token, String key) throws Exception {
        return client.send(HttpRequest.newBuilder(uri("/api/batches")).timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private JsonNode body(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
    private static String idempotency() { return "jwks-" + UUID.randomUUID(); }

    private static String token(KeyPair key, String keyId, String tenant, String subject,
                                String scopes, JWSAlgorithm algorithm) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).subject(subject)
            .expirationTime(Date.from(Instant.now().plusSeconds(300))).claim("scope", scopes)
            .claim("tenant_id", tenant).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(algorithm).keyID(keyId).build(), claims);
        jwt.sign(new RSASSASigner(key.getPrivate()));
        return jwt.serialize();
    }

    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(ENDPOINT_THREADS);
            server.createContext("/jwks", exchange -> {
                FETCHES.incrementAndGet();
                var hold = HOLD.get();
                var entered = ENTERED.get();
                if (hold != null) {
                    if (entered != null) entered.countDown();
                    try { hold.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); exchange.close(); return; }
                }
                byte[] body = (STATUS.get() == 200 ? JWKS.get() : "synthetic-jwks-body-marker")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/jwk-set+json");
                exchange.getResponseHeaders().set("Cache-Control", "max-age=3600");
                exchange.sendResponseHeaders(STATUS.get(), body.length);
                boolean slow = SLOW.get();
                try (var output = exchange.getResponseBody()) {
                    if (slow) {
                        for (byte value : body) {
                            output.write(value);
                            output.flush();
                            try { Thread.sleep(100); }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                        }
                    } else output.write(body);
                } catch (java.io.IOException disconnected) {
                    if (!slow) throw disconnected;
                } finally { exchange.close(); }
            });
            server.start();
            return server;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String jwks(String keyId, KeyPair key) {
        var jwk = new RSAKey.Builder((RSAPublicKey) key.getPublic()).keyID(keyId)
            .algorithm(JWSAlgorithm.RS256).build();
        return "{\"keys\":[" + jwk.toPublicJWK().toJSONString() + "]}";
    }

    private static KeyPair keyPair() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
