package dev.harshaattili.reconledger;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"recon.rate-limit.enabled=true", "recon.security.review-ui-enabled=true"})
@ActiveProfiles({"test", "secured"})
@Import(RateLimitHttpTest.ClockConfiguration.class)
class RateLimitHttpTest {
    private static final AtomicLong CLOCK = new AtomicLong();
    private static final KeyPair KEY = keyPair();
    private static final Path PUBLIC_KEY = publicKeyFile();
    private static final String ISSUER = "https://issuer.example.invalid/rate-test";
    private static final String BATCH = """
        {"businessDate":"2026-10-17","currency":"USD",
         "leftRecords":[{"recordId":"left","reference":"RATE-1","amount":"10.00"}],
         "rightRecords":[]}
        """;
    private static final String REVIEW = """
        {"targetState":"IN_REVIEW","expectedVersion":0,"note":"Synthetic rate check."}
        """;
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean @Primary RequestRateBudget controlledBudget() {
            return new RequestRateBudget(2, 60_000, CLOCK::get);
        }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("recon.security.public-key", () -> PUBLIC_KEY.toUri().toString());
        registry.add("recon.security.issuer", () -> ISSUER);
        registry.add("recon.security.audience", () -> "rate-test");
    }
    @AfterAll static void cleanup() throws Exception { Files.deleteIfExists(PUBLIC_KEY); }
    @BeforeEach void refill() { CLOCK.addAndGet(TimeUnit.HOURS.toNanos(1)); }

    @Test void rejectsBeforeParsingOrReservingKeysAndRecoversWithSameKey() throws Exception {
        String token = token("rate-a", "recon:read recon:write recon:review");
        String key = "rate-" + UUID.randomUUID();
        // Admitted invalid requests consume budget as well; failures are not refunded.
        assertThat(call("POST", "/api/batches", "{", token, key).statusCode()).isEqualTo(400);
        assertThat(call("GET", "/api/batches?limit=0", null, token, null).statusCode()).isEqualTo(400);
        var rejected = call("POST", "/api/batches", BATCH, token, key);
        assertRejected(rejected);
        assertRejected(call("POST", "/api/batches", "{", token, key));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recon_batch WHERE idempotency_key = ?",
            Integer.class, key)).isZero();
        CLOCK.addAndGet(TimeUnit.SECONDS.toNanos(60));
        var created = call("POST", "/api/batches", BATCH, token, key);
        assertThat(created.statusCode()).isEqualTo(201);
        assertRejected(call("POST", "/api/batches", BATCH, token, key));
        CLOCK.addAndGet(TimeUnit.SECONDS.toNanos(60));
        var replay = call("POST", "/api/batches", BATCH, token, key);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(body(replay).get("id")).isEqualTo(body(created).get("id"));
    }

    @Test void rejectedReviewsLeaveStateAndAuditUntouchedAndAllDataRoutesShareBudget() throws Exception {
        String token = token("rate-b", "recon:read recon:write recon:review");
        var created = body(call("POST", "/api/batches", BATCH, token, "rate-" + UUID.randomUUID()));
        String batchId = created.get("id").asText();
        String id = created.at("/results/0/id").asText();
        assertThat(call("GET", "/api/review-session", null, token, null).statusCode()).isEqualTo(200);
        assertRejected(call("POST", "/api/results/" + id + "/reviews", REVIEW, token, null));
        for (String path : List.of("/api/batches", "/api/batches/" + batchId,
                "/api/results/" + id, "/api/results/" + id + "/events", "/api/review-session")) {
            assertRejected(call("GET", path, null, token, null));
        }
        assertThat(jdbc.queryForObject("SELECT version FROM recon_result WHERE id = ?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_event WHERE result_id = ?",
            Integer.class, id)).isZero();
        CLOCK.addAndGet(TimeUnit.SECONDS.toNanos(60));
        var saved = call("POST", "/api/results/" + id + "/reviews", REVIEW, token, null);
        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(body(saved).get("version").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_event WHERE result_id = ?",
            Integer.class, id)).isEqualTo(1);
    }

    @Test void authenticationPrecedesBudgetAndHealthAssetsRemainAvailable() throws Exception {
        String reader = token("rate-c", "recon:read");
        assertThat(call("GET", "/api/batches", null, null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/api/batches", null, "invalid-token", null).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/api/batches", BATCH, reader, "rate-" + UUID.randomUUID()).statusCode())
            .isEqualTo(403);
        assertThat(call("GET", "/api/batches", null, reader, null).statusCode()).isEqualTo(200);
        // New subject/tenant/token does not obtain a separate process budget.
        String other = token("rate-d", "recon:read");
        assertThat(call("GET", "/api/batches", null, other, null).statusCode()).isEqualTo(200);
        assertRejected(call("GET", "/api/batches", null, other, null));
        assertThat(call("GET", "/api/batches", null, null, null).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/api/batches", BATCH, reader, null).statusCode()).isEqualTo(403);
        for (String path : List.of("/actuator/health", "/review/index.html", "/review/review.js", "/review/review.css")) {
            assertThat(call("GET", path, null, null, null).statusCode()).as(path).isEqualTo(200);
        }
        assertRejected(call("GET", "/api/batches", null, reader, null));
    }

    private void assertRejected(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Retry-After")).contains("60");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(body(response).get("status").asInt()).isEqualTo(429);
    }
    private JsonNode body(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
    private HttpResponse<String> call(String method, String path, String body, String token, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String token(String tenant, String scope) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience("rate-test")
            .subject("reviewer-" + tenant).claim("tenant_id", tenant).claim("scope", scope)
            .expirationTime(Date.from(Instant.now().plusSeconds(600))).build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(KEY.getPrivate()));
        return jwt.serialize();
    }
    private static KeyPair keyPair() {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static Path publicKeyFile() {
        try {
            Path path = Files.createTempFile("recon-rate-public-", ".pem");
            String encoded = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(KEY.getPublic().getEncoded());
            Files.writeString(path, "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----\n");
            return path;
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
