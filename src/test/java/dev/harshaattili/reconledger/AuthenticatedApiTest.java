package dev.harshaattili.reconledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "secured"})
class AuthenticatedApiTest {
    private static final String ISSUER = "https://issuer.example.invalid/recon-test";
    private static final String AUDIENCE = "reconledger-test";
    private static final KeyPair KEY = keyPair();
    private static final Path PUBLIC_KEY = publicKeyFile();
    private static final String BATCH = """
        {"businessDate":"2026-10-11","currency":"USD",
         "leftRecords":[{"recordId":"left-1","reference":"INV-1","amount":"10.00"}],
         "rightRecords":[]}
        """;
    private static final String REVIEW = """
        {"targetState":"IN_REVIEW","expectedVersion":0,"note":"Checking synthetic evidence."}
        """;

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired LedgerRepository repository;
    @Value("${recon.test.expected-database}") String expectedDatabase;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("recon.security.public-key", () -> PUBLIC_KEY.toUri().toString());
        registry.add("recon.security.issuer", () -> ISSUER);
        registry.add("recon.security.audience", () -> AUDIENCE);
    }

    @AfterAll static void removeKeyFile() throws Exception { Files.deleteIfExists(PUBLIC_KEY); }

    @Test void requiresBearerAuthenticationOnEveryDataEndpoint() throws Exception {
        var health = call("GET", "/actuator/health", null, null, null);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(body(health)).isEqualTo(json.readTree("{\"status\":\"UP\"}"));
        for (String path : List.of("/api/batches", "/api/batches/unknown", "/api/results/unknown",
                                   "/api/results/unknown/events")) {
            var denied = call("GET", path, null, null, null);
            assertThat(denied.statusCode()).as(path).isEqualTo(401);
            assertThat(denied.headers().firstValue("WWW-Authenticate").orElse("")).startsWith("Bearer");
        }
        String key = key();
        assertThat(call("POST", "/api/batches", BATCH, null, key).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/api/results/unknown/reviews", REVIEW, null, null).statusCode()).isEqualTo(401);
        assertUnreserved(key);
        assertThat(call("GET", "/api/batches", null, "Basic ZGVtbzpkZW1v", null).statusCode()).isEqualTo(401);
        var forged = HttpRequest.newBuilder(uri("/api/batches"))
            .header("X-Forwarded-User", "reviewer").header("X-User", "reviewer").GET().build();
        assertThat(client.send(forged, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    @Test void validatesSignatureAlgorithmIssuerAudienceTimesAndSubjectBeforeWrites() throws Exception {
        var badClaims = List.of(
            claims("reviewer", "recon:write").issuer("https://wrong.example.invalid"),
            claims("reviewer", "recon:write").audience("another-api"),
            claims("reviewer", "recon:write").audience((List<String>) null),
            claims("reviewer", "recon:write").expirationTime(Date.from(Instant.now().minusSeconds(600))),
            claims("reviewer", "recon:write").notBeforeTime(Date.from(Instant.now().plusSeconds(600))),
            claims("reviewer", "recon:write").expirationTime(null),
            claims("reviewer", "recon:write").subject(null),
            claims(" ", "recon:write"), claims(" spaced ", "recon:write"),
            claims("x".repeat(81), "recon:write"));
        String key = key();
        for (var claims : badClaims) {
            assertThat(call("POST", "/api/batches", BATCH, bearer(sign(claims, KEY, JWSAlgorithm.RS256)), key)
                .statusCode()).isEqualTo(401);
        }
        for (String bad : List.of("not-a-jwt", sign(claims("reviewer", "recon:write"), keyPair(), JWSAlgorithm.RS256),
                sign(claims("reviewer", "recon:write"), KEY, JWSAlgorithm.RS512))) {
            assertThat(call("POST", "/api/batches", BATCH, bearer(bad), key).statusCode()).isEqualTo(401);
        }
        assertUnreserved(key);
        assertThat(call("POST", "/api/batches", BATCH, token("creator", "recon:write"), key).statusCode())
            .isEqualTo(201);
    }

    @Test void separatesReadCreateAndReviewPermissionsAndDeniesUnknownRoutes() throws Exception {
        var created = create();
        String batchId = created.get("id").asText();
        String id = created.at("/results/0/id").asText();
        String reader = token("reader", "recon:read");
        for (String path : List.of("/api/batches?limit=1", "/api/batches/" + batchId,
                                   "/api/results/" + id, "/api/results/" + id + "/events")) {
            assertThat(call("GET", path, null, reader, null).statusCode()).isEqualTo(200);
        }
        String key = key();
        assertThat(call("POST", "/api/batches", BATCH, reader, key).statusCode()).isEqualTo(403);
        assertUnreserved(key);
        assertThat(call("POST", "/api/results/" + id + "/reviews", REVIEW, reader, null).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/api/results/" + id + "/reviews", REVIEW, token("writer", "recon:write"), null)
            .statusCode()).isEqualTo(403);
        assertThat(call("GET", "/api/batches", null, token("reviewer", "recon:review"), null).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/api/batches", null, token("unscoped", ""), null).statusCode()).isEqualTo(403);
        assertThat(body(call("GET", "/api/results/" + id, null, reader, null)).get("version").asInt()).isZero();
        assertThat(body(call("GET", "/api/results/" + id + "/events", null, reader, null)).get("events")).isEmpty();
        String all = token("operator", "recon:read recon:write recon:review");
        assertThat(call("PUT", "/api/batches/" + batchId, BATCH, all, null).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/api/unmapped", null, all, null).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/actuator/env", null, all, null).statusCode()).isEqualTo(403);
        for (String path : List.of("/review/index.html", "/review/review.js", "/review/review.css")) {
            assertThat(call("GET", path, null, null, null).statusCode()).isEqualTo(401);
            assertThat(call("GET", path, null, all, null).statusCode()).isEqualTo(403);
        }
    }

    @Test void recordsVerifiedSubjectAndRejectsCallerSelectedActorWithoutMutation() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo(expectedDatabase);
        }
        String id = create().at("/results/0/id").asText();
        String reviewer = token("verified-reviewer", "recon:read recon:review");
        var spoof = json.readTree(REVIEW).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) spoof).put("actor", "another-person");
        assertThat(call("POST", "/api/results/" + id + "/reviews", json.writeValueAsString(spoof), reviewer, null)
            .statusCode()).isEqualTo(400);
        assertThat(body(call("GET", "/api/results/" + id, null, reviewer, null)).get("version").asInt()).isZero();
        assertThat(body(call("GET", "/api/results/" + id + "/events", null, reviewer, null)).get("events")).isEmpty();
        for (String field : List.of("actorSource", "actorIssuer")) {
            var forged = json.readTree(REVIEW).deepCopy();
            ((tools.jackson.databind.node.ObjectNode) forged).put(field, "caller-selected");
            assertThat(call("POST", "/api/results/" + id + "/reviews", json.writeValueAsString(forged), reviewer, null)
                .statusCode()).isEqualTo(400);
        }
        assertThat(body(call("GET", "/api/results/" + id, null, reviewer, null)).get("version").asInt()).isZero();
        var accepted = call("POST", "/api/results/" + id + "/reviews", REVIEW, reviewer, null);
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(body(accepted).get("version").asInt()).isEqualTo(1);
        assertThat(call("POST", "/api/results/" + id + "/reviews", REVIEW, reviewer, null).statusCode()).isEqualTo(409);
        var events = body(call("GET", "/api/results/" + id + "/events", null, reviewer, null)).get("events");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("actor").asText()).isEqualTo("verified-reviewer");
        assertThat(events.get(0).get("actorSource").asText()).isEqualTo("VERIFIED_JWT");
        assertThat(events.get(0).get("actorIssuer").asText()).isEqualTo(ISSUER);
    }

    @Test void preservesRequestBoundsAndMvcErrorsAfterAuthentication() throws Exception {
        String writer = token("writer", "recon:read recon:write");
        String key = key();
        var oversized = BATCH + " ".repeat(RequestBodyLimitAdvice.MAX_BYTES);
        assertThat(call("POST", "/api/batches", oversized, writer, key).statusCode()).isEqualTo(413);
        assertThat(call("POST", "/api/batches", "{broken", writer, key).statusCode()).isEqualTo(400);
        assertUnreserved(key);
        assertThat(call("GET", "/api/batches?limit=101", null, writer, null).statusCode()).isEqualTo(400);
        assertThat(call("GET", "/api/results/unknown", null, writer, null).statusCode()).isEqualTo(404);
        assertThat(call("POST", "/api/batches", BATCH, writer, key).statusCode()).isEqualTo(201);
        assertThat(call("POST", "/api/batches", BATCH, writer, key).statusCode()).isEqualTo(200);
    }

    @Test void doesNotAuthenticateCookiesOrQueryTokensAndDoesNotCreateSessions() throws Exception {
        String raw = sign(claims("reader", "recon:read"), KEY, JWSAlgorithm.RS256);
        var accepted = call("GET", "/api/batches", null, bearer(raw), null);
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(accepted.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(call("GET", "/api/batches", null, null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/api/batches?access_token=" + raw, null, null, null).statusCode()).isEqualTo(401);
        var cookie = HttpRequest.newBuilder(uri("/api/batches")).header("Cookie", "access_token=" + raw).GET().build();
        assertThat(client.send(cookie, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    @Test void rejectsBlankTrustConfigurationAndInvalidPublicKey() {
        var config = new ApiSecurity();
        var badKey = new ByteArrayResource("not a PEM key".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> config.jwtDecoder(badKey, " ", AUDIENCE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.jwtDecoder(badKey, ISSUER, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.jwtDecoder(badKey, "x".repeat(2049), AUDIENCE))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2048");
        assertThatThrownBy(() -> config.jwtDecoder(badKey, ISSUER, AUDIENCE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void requiresAnIssuerSuppliedTenantAndIgnoresTenantHeaders() throws Exception {
        String key = key();
        for (Object tenant : List.of("", "demo", "Tenant-A", "../other", "x".repeat(65), 42, List.of("tenant-a"))) {
            String jwt = bearer(sign(claims("reader", "recon:write").claim("tenant_id", tenant), KEY, JWSAlgorithm.RS256));
            assertThat(call("POST", "/api/batches", BATCH, jwt, key).statusCode()).isEqualTo(401);
        }
        String missing = bearer(sign(claims("reader", "recon:write").claim("tenant_id", null), KEY, JWSAlgorithm.RS256));
        var request = HttpRequest.newBuilder(uri("/api/batches")).header("Authorization", missing)
            .header("X-Tenant-Id", "tenant-a").GET().build();
        assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertUnreserved(key);
        String injected = BATCH.replace("\"currency\":\"USD\"", "\"currency\":\"USD\",\"tenant_id\":\"other\"");
        assertThat(call("POST", "/api/batches", injected, token("writer", "recon:write"), key).statusCode()).isEqualTo(400);
        assertUnreserved(key);
    }

    @Test void isolatesDetailAuditReviewAndFilteredPagesEvenWithKnownForeignIds() throws Exception {
        String a = "a-" + UUID.randomUUID();
        String b = "b-" + UUID.randomUUID();
        String scopes = "recon:read recon:write recon:review";
        String tokenA = tenantToken(a, scopes);
        String tokenB = tenantToken(b, scopes);
        var first = body(call("POST", "/api/batches", BATCH, tokenA, key()));
        var foreign = body(call("POST", "/api/batches", BATCH, tokenB, key()));
        var last = body(call("POST", "/api/batches", BATCH, tokenA, key()));
        String batchId = first.get("id").asText();
        String id = first.at("/results/0/id").asText();
        String foreignId = foreign.at("/results/0/id").asText();
        for (String path : List.of("/api/batches/" + batchId, "/api/results/" + id, "/api/results/" + id + "/events")) {
            assertThat(call("GET", path, null, tokenB, null).statusCode()).isEqualTo(404);
            assertThat(call("GET", path, null, tokenA, null).statusCode()).isEqualTo(200);
        }
        assertThat(call("POST", "/api/results/" + id + "/reviews", REVIEW, tokenB, null).statusCode()).isEqualTo(404);
        assertThat(body(call("GET", "/api/results/" + id, null, tokenA, null)).get("version").asInt()).isZero();
        assertThat(body(call("GET", "/api/results/" + id + "/events", null, tokenA, null)).get("events")).isEmpty();
        String query = "/api/batches?limit=1&businessDate=2026-10-11&currency=USD";
        var page = body(call("GET", query, null, tokenA, null));
        assertThat(page.at("/batches/0/id").asText()).isEqualTo(last.get("id").asText());
        var next = body(call("GET", query + "&beforeSequence=" + page.get("nextBeforeSequence").asLong(), null, tokenA, null));
        assertThat(next.at("/batches/0/id").asText()).isEqualTo(batchId);
        assertThat(next.get("nextBeforeSequence").isNull()).isTrue();
        var forged = HttpRequest.newBuilder(uri("/api/batches/" + batchId)).header("Authorization", tokenB)
            .header("X-Tenant-Id", a).GET().build();
        assertThat(client.send(forged, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        assertThat(call("POST", "/api/results/" + id + "/reviews", REVIEW, tokenA, null).statusCode()).isEqualTo(200);
        assertThat(body(call("GET", "/api/results/" + foreignId, null, tokenB, null)).get("version").asInt()).isZero();
        assertThat(body(call("GET", "/api/results/" + id + "/events", null, tokenA, null)).at("/events/0/actor").asText())
            .isEqualTo("shared-subject");
    }

    @Test void concurrentRetriesDeduplicateWithinEachTenantButAllowTheSameKeyAcrossTenants() throws Exception {
        String a = "a-" + UUID.randomUUID();
        String b = "b-" + UUID.randomUUID();
        String tokenA = tenantToken(a, "recon:read recon:write");
        String tokenB = tenantToken(b, "recon:read recon:write");
        String key = key();
        String changed = BATCH.replace("10.00", "11.00");
        var executor = java.util.concurrent.Executors.newFixedThreadPool(6);
        var start = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
        try {
            for (int index = 0; index < 6; index++) {
                boolean firstTenant = index % 2 == 0;
                futures.add(executor.submit(() -> {
                    start.await();
                    return call("POST", "/api/batches", firstTenant ? BATCH : changed, firstTenant ? tokenA : tokenB, key);
                }));
            }
            start.countDown();
            var idsA = new java.util.HashSet<String>();
            var idsB = new java.util.HashSet<String>();
            int created = 0;
            for (int index = 0; index < futures.size(); index++) {
                var response = futures.get(index).get(20, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(response.statusCode()).isIn(200, 201);
                if (response.statusCode() == 201) created++;
                (index % 2 == 0 ? idsA : idsB).add(body(response).get("id").asText());
            }
            assertThat(created).isEqualTo(2);
            assertThat(idsA).hasSize(1).doesNotContainAnyElementsOf(idsB);
            assertThat(idsB).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recon_batch WHERE idempotency_key = ?", Integer.class, key))
                .isEqualTo(2);
            assertThat(call("POST", "/api/batches", changed, tokenA, key).statusCode()).isEqualTo(409);
            assertThat(call("POST", "/api/batches", BATCH, tokenB, key).statusCode()).isEqualTo(409);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test void securedRepositoryDoesNotFallBackToDemoWithoutARequestIdentity() {
        assertThatThrownBy(() -> repository.batches(null, 1, null, null))
            .isInstanceOfSatisfying(ApiException.class,
                failure -> assertThat(failure.status()).isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED));
    }

    @Test void reviewContextUsesVerifiedIdentityAndReadScopeWithoutCreatingSession() throws Exception {
        String path = "/api/review-session";
        assertThat(call("GET", path, null, null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", path, null, token("writer", "recon:write"), null).statusCode()).isEqualTo(403);
        var read = call("GET", path, null, token("reader", "recon:read"), null);
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(read.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(body(read).get("secured").asBoolean()).isTrue();
        assertThat(body(read).get("subject").asText()).isEqualTo("reader");
        assertThat(body(read).get("issuer").asText()).isEqualTo(ISSUER);
        assertThat(body(read).get("tenant").asText()).isEqualTo("auth-test");
        assertThat(body(read).get("canReview").asBoolean()).isFalse();
        var reviewer = call("GET", path, null, tenantToken("other-tenant", "recon:read recon:review"), null);
        assertThat(body(reviewer).get("tenant").asText()).isEqualTo("other-tenant");
        assertThat(body(reviewer).get("canReview").asBoolean()).isTrue();
        assertThat(body(reviewer).size()).isEqualTo(5);
        assertThat(call("POST", path, "{}", token("reader", "recon:read"), null).statusCode()).isEqualTo(403);
    }

    private static String tenantToken(String tenant, String scopes) throws Exception {
        return bearer(sign(claims("shared-subject", scopes).claim("tenant_id", tenant), KEY, JWSAlgorithm.RS256));
    }

    private JsonNode create() throws Exception {
        var response = call("POST", "/api/batches", BATCH, token("creator", "recon:write"), key());
        assertThat(response.statusCode()).isEqualTo(201);
        return body(response);
    }

    private void assertUnreserved(String key) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recon_batch WHERE idempotency_key = ?", Integer.class, key)).isZero();
    }

    private HttpResponse<String> call(String method, String path, String body, String authorization, String key) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json");
        if (authorization != null) request.header("Authorization", authorization);
        if (key != null) request.header("Idempotency-Key", key);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private JsonNode body(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
    private static String key() { return "security-" + UUID.randomUUID(); }
    private static String bearer(String raw) { return "Bearer " + raw; }
    private static String token(String subject, String scopes) throws Exception {
        return bearer(sign(claims(subject, scopes), KEY, JWSAlgorithm.RS256));
    }
    private static JWTClaimsSet.Builder claims(String subject, String scopes) {
        return new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).subject(subject)
            .expirationTime(Date.from(Instant.now().plusSeconds(300))).claim("scope", scopes).claim("tenant_id", "auth-test");
    }
    private static String sign(JWTClaimsSet.Builder claims, KeyPair key, JWSAlgorithm algorithm) throws Exception {
        var token = new SignedJWT(new JWSHeader(algorithm), claims.build());
        token.sign(new RSASSASigner(key.getPrivate()));
        return token.serialize();
    }
    private static KeyPair keyPair() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static Path publicKeyFile() {
        try {
            Path path = Files.createTempFile("recon-public-test-", ".pem");
            String encoded = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(KEY.getPublic().getEncoded());
            Files.writeString(path, "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----\n");
            return path;
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
