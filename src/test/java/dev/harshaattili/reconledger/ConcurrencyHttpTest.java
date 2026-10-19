package dev.harshaattili.reconledger;

import static org.assertj.core.api.Assertions.assertThat;
import tools.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"recon.concurrency-limit.enabled=true", "recon.concurrency-limit.capacity=2",
        "recon.concurrency-limit.per-tenant-capacity=1",
        "recon.security.review-ui-enabled=true"})
@ActiveProfiles({"test", "secured"})
@Import(ConcurrencyHttpTest.Observation.class)
class ConcurrencyHttpTest {
    private static final KeyPair KEY = keyPair();
    private static final Path PUBLIC_KEY = publicKeyFile();
    private static final String ISSUER = "https://issuer.example.invalid/concurrency-test";
    private static final String BATCH = """
        {"businessDate":"2026-10-18","currency":"USD",
         "leftRecords":[{"recordId":"left","reference":"CAP-1","amount":"10.00"}],
         "rightRecords":[]}
        """;
    private static volatile CountDownLatch entered;
    private static volatile CountDownLatch completed;
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();

    @TestConfiguration(proxyBeanMethods = false)
    static class Observation {
        @Bean WebMvcConfigurer observeAdmission() {
            return new WebMvcConfigurer() {
                @Override public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(new HandlerInterceptor() {
                        @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception failure) {
                            if ("stalled".equals(request.getHeader("X-Test-Upload"))) completed.countDown();
                        }
                    }).order(-20); // Completion runs after the production admission releases.
                    registry.addInterceptor(new HandlerInterceptor() {
                        @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                            if ("stalled".equals(request.getHeader("X-Test-Upload"))) entered.countDown();
                            return true;
                        }
                    }).order(10); // Observe only requests that acquired a production slot.
                }
            };
        }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("recon.security.public-key", () -> PUBLIC_KEY.toUri().toString());
        registry.add("recon.security.issuer", () -> ISSUER);
        registry.add("recon.security.audience", () -> "concurrency-test");
    }
    @AfterAll static void cleanup() throws Exception { Files.deleteIfExists(PUBLIC_KEY); }

    @Test void stalledUploadsShareCapacityAcrossTenantsAndRejectedWritesHaveNoEffects() throws Exception {
        String writer = token("capacity-a", "recon:read recon:write recon:review");
        String other = token("capacity-b", "recon:read recon:write recon:review");
        String key = "capacity-" + UUID.randomUUID();
        var created = call("POST", "/api/batches", BATCH, writer, key);
        assertThat(created.statusCode()).isEqualTo(201);
        String id = json.readTree(created.body()).at("/results/0/id").asText();
        String rejectedKey = "capacity-" + UUID.randomUUID();
        try (var held = holdUploads(writer, other)) {
            assertBusy(call("POST", "/api/batches", "{", other, rejectedKey));
            assertBusy(call("POST", "/api/results/" + id + "/reviews",
                "{\"targetState\":\"IN_REVIEW\",\"expectedVersion\":0,\"note\":\"synthetic\"}", writer, null));
            for (String path : List.of("/api/batches", "/api/batches/missing", "/api/results/" + id,
                    "/api/results/" + id + "/events", "/api/review-session")) {
                assertBusy(call("GET", path, null, writer, null));
            }
            assertThat(call("GET", "/api/batches", null, null, null).statusCode()).isEqualTo(401);
            assertThat(call("POST", "/api/batches", BATCH, token("capacity-a", "recon:read"), key).statusCode()).isEqualTo(403);
            for (String path : List.of("/actuator/health", "/review/index.html", "/review/review.js", "/review/review.css")) {
                assertThat(call("GET", path, null, null, null).statusCode()).as(path).isEqualTo(200);
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recon_batch WHERE idempotency_key = ?", Integer.class, rejectedKey)).isZero();
            assertThat(jdbc.queryForObject("SELECT version FROM recon_result WHERE id = ?", Integer.class, id)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_event WHERE result_id = ?", Integer.class, id)).isZero();
        }
        assertThat(call("POST", "/api/batches", BATCH, other, rejectedKey).statusCode()).isEqualTo(201);
        // Both recovery uploads must hold their slots together; sequential success is insufficient.
        try (var held = holdUploads(writer, other)) {
            assertBusy(call("GET", "/api/batches", null, writer, null));
        }
    }

    @Test void parsingOwnershipAndBodyRejectionsReleaseAllSlots() throws Exception {
        String token = token("capacity-errors", "recon:read recon:write");
        for (int i = 0; i < 3; i++) {
            assertThat(call("POST", "/api/batches", "{", token, "capacity-" + UUID.randomUUID()).statusCode()).isEqualTo(400);
            assertThat(call("POST", "/api/batches", BATCH + " ".repeat(524288), token, "capacity-" + UUID.randomUUID()).statusCode()).isEqualTo(413);
            assertThat(call("GET", "/api/batches/missing", null, token, null).statusCode()).isEqualTo(404);
        }
        try (var held = holdUploads(token, token("capacity-other-errors", "recon:read recon:write"))) {
            assertBusy(call("GET", "/api/batches", null, token, null));
        }
    }

    @Test void oneTenantCannotOccupyTheOtherSlotOrBypassItsCeilingWithAnotherSubject() throws Exception {
        String writer = token("tenant-ceiling-a", "recon:read recon:write recon:review");
        String colleague = token("tenant-ceiling-a", "recon:read recon:write recon:review", "second-reviewer");
        String other = token("tenant-ceiling-b", "recon:read recon:write");
        var created = call("POST", "/api/batches", BATCH, writer, "tenant-cap-" + UUID.randomUUID());
        assertThat(created.statusCode()).isEqualTo(201);
        String id = json.readTree(created.body()).at("/results/0/id").asText();
        for (boolean chunked : new boolean[]{false, true}) {
            String retryKey = "tenant-cap-" + UUID.randomUUID();
            entered = new CountDownLatch(1);
            completed = new CountDownLatch(1);
            try (var socket = new Socket("127.0.0.1", port)) {
                sendPartial(socket, writer, chunked);
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertBusy(call("GET", "/api/review-session", null, colleague, null));
                assertBusy(call("POST", "/api/batches", BATCH, colleague, retryKey));
                assertBusy(call("POST", "/api/results/" + id + "/reviews",
                    "{\"targetState\":\"IN_REVIEW\",\"expectedVersion\":0,\"note\":\"synthetic\"}", colleague, null));
                // Same subject, different signed tenant can still read and write.
                assertThat(call("GET", "/api/review-session", null, other, null).statusCode()).isEqualTo(200);
                assertThat(call("POST", "/api/batches", BATCH, other, retryKey).statusCode()).isEqualTo(201);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recon_batch WHERE tenant_id = ? AND idempotency_key = ?",
                    Integer.class, "tenant-ceiling-a", retryKey)).isZero();
                assertThat(jdbc.queryForObject("SELECT version FROM recon_result WHERE id = ?", Integer.class, id)).isZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_event WHERE result_id = ?", Integer.class, id)).isZero();
            } finally { assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue(); }
            assertThat(call("POST", "/api/batches", BATCH, colleague, retryKey).statusCode()).isEqualTo(201);
        }
        try (var held = holdUploads(writer, other)) {
            assertBusy(call("GET", "/api/review-session", null, token("tenant-ceiling-c", "recon:read"), null));
        }
    }

    private HeldUploads holdUploads(String firstToken, String secondToken) throws Exception {
        entered = new CountDownLatch(2);
        completed = new CountDownLatch(2);
        var held = new HeldUploads(new Socket("127.0.0.1", port), new Socket("127.0.0.1", port));
        try {
            sendPartial(held.first, firstToken, false);
            sendPartial(held.second, secondToken, true);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            // A successful session read would mean the two partial bodies had not occupied capacity.
            assertBusy(call("GET", "/api/review-session", null, firstToken, null));
            return held;
        } catch (Throwable failure) { held.close(); throw failure; }
    }
    private void sendPartial(Socket socket, String token, boolean chunked) throws Exception {
        String headers = "POST /api/batches HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + token
            + "\r\nIdempotency-Key: held-" + UUID.randomUUID()
            + "\r\nContent-Type: application/json\r\nX-Test-Upload: stalled\r\n"
            + (chunked ? "Transfer-Encoding: chunked\r\n\r\n1\r\n{\r\n" : "Content-Length: 100\r\n\r\n{");
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }
    private record HeldUploads(Socket first, Socket second) implements AutoCloseable {
        @Override public void close() throws Exception {
            first.close(); second.close();
            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }
    private void assertBusy(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("Retry-After")).isEmpty();
        assertThat(json.readTree(response.body()).get("detail").asText()).contains("concurrency capacity exhausted");
    }
    private HttpResponse<String> call(String method, String path, String body, String token, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (token != null) request.header("Authorization", "Bearer " + token);
        // Request headers never select the admission identity, including while A is full.
        request.header("X-Tenant-Id", "forged-spare-tenant");
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String token(String tenant, String scope) throws Exception {
        return token(tenant, scope, "synthetic-reviewer");
    }
    private static String token(String tenant, String scope, String subject) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience("concurrency-test").subject(subject)
            .claim("tenant_id", tenant).claim("scope", scope).expirationTime(Date.from(Instant.now().plusSeconds(600))).build();
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
            Path path = Files.createTempFile("recon-concurrency-public-", ".pem");
            String encoded = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(KEY.getPublic().getEncoded());
            Files.writeString(path, "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----\n");
            return path;
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
