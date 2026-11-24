package dev.harshaattili.reconledger;

import static org.assertj.core.api.Assertions.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "secured"})
@DirtiesContext
class JwksFetchIntervalHttpTest {
    private static final KeyPair KEY = key();
    private static final AtomicInteger FETCHES = new AtomicInteger();
    private static final HttpServer SERVER = server();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("recon.security.jwk-set-uri", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/jwks");
        registry.add("recon.security.issuer", () -> "https://issuer.example.invalid/interval");
        registry.add("recon.security.audience", () -> "interval-test");
        registry.add("recon.security.jwks-min-fetch-interval-millis", () -> "60000");
    }
    @AfterAll static void stop() { SERVER.stop(0); }

    @Test void configuredBudgetReturnsSanitized503BeforeWritesAndRetainsDefaultCachedKey() throws Exception {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String known = token("known"), unknown = token("unknown");
        URI base = URI.create("http://127.0.0.1:" + port);
        var read = HttpRequest.newBuilder(base.resolve("/api/batches"))
            .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + known).GET().build();
        assertThat(client.send(read, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(FETCHES.get()).isEqualTo(1);
        String pending = "interval-" + UUID.randomUUID();
        var write = HttpRequest.newBuilder(base.resolve("/api/batches"))
            .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + unknown)
            .header("Content-Type", "application/json").header("Idempotency-Key", pending)
            .POST(HttpRequest.BodyPublishers.ofString("{}" )).build();
        for (int i = 0; i < 3; i++) {
            var rejected = client.send(write, HttpResponse.BodyHandlers.ofString());
            assertThat(rejected.statusCode()).isEqualTo(503);
            assertThat(rejected.body()).isEqualTo("{\"title\":\"Token verification unavailable\",\"status\":503}");
            assertThat(rejected.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(rejected.headers().firstValue("WWW-Authenticate")).isEmpty();
            assertThat(rejected.headers().firstValue("Retry-After")).isEmpty();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recon_batch WHERE idempotency_key = ?",
            Integer.class, pending)).isZero();
        assertThat(client.send(read, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(FETCHES.get()).isEqualTo(1);
    }

    private static KeyPair key() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException failure) { throw new IllegalStateException(failure); }
    }
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> {
                FETCHES.incrementAndGet();
                byte[] bytes = ("{\"keys\":[" + new RSAKey.Builder((RSAPublicKey) KEY.getPublic())
                    .keyID("known").build().toJSONString() + "]}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
                finally { exchange.close(); }
            });
            server.start();
            return server;
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    private static String token(String kid) throws Exception {
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(),
            new JWTClaimsSet.Builder().issuer("https://issuer.example.invalid/interval")
                .audience("interval-test").subject("synthetic").claim("tenant_id", "interval-test")
                .claim("scope", "recon:read recon:write")
                .expirationTime(Date.from(Instant.now().plusSeconds(900))).build());
        token.sign(new RSASSASigner(KEY.getPrivate()));
        return token.serialize();
    }
}
