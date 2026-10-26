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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "secured"})
class JwksRotationHttpTest {
    private static final String ISSUER = "https://issuer.example.invalid/jwks-test";
    private static final String AUDIENCE = "reconledger-jwks-test";
    private static final KeyPair KEY_A = keyPair();
    private static final KeyPair KEY_B = keyPair();
    private static final AtomicReference<String> JWKS = new AtomicReference<>(jwks("key-a", KEY_A));
    private static final AtomicInteger FETCHES = new AtomicInteger();
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
        registry.add("recon.security.jwk-set-uri", () -> JWK_SET_URI);
        registry.add("recon.security.issuer", () -> ISSUER);
        registry.add("recon.security.audience", () -> AUDIENCE);
    }

    @AfterAll static void stopServer() { SERVER.stop(0); }

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
            server.createContext("/jwks", exchange -> {
                FETCHES.incrementAndGet();
                byte[] body = JWKS.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/jwk-set+json");
                exchange.getResponseHeaders().set("Cache-Control", "max-age=3600");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
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
