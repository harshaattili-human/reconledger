package dev.harshaattili.reconledger;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "server.servlet.context-path=/ledger")
@ActiveProfiles({"test", "secured"})
class ContextPathCachingHttpTest {
    private static final String ISSUER = "https://issuer.example.invalid/context-test";
    private static final String AUDIENCE = "context-test";
    private static final KeyPair KEY = keyPair();
    private static final Path PUBLIC_KEY = publicKeyFile();
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("recon.security.public-key", () -> PUBLIC_KEY.toUri().toString());
        registry.add("recon.security.issuer", () -> ISSUER);
        registry.add("recon.security.audience", () -> AUDIENCE);
    }
    @AfterAll static void removeKeyFile() throws Exception { Files.deleteIfExists(PUBLIC_KEY); }

    @Test void appliesNoStoreBeforeSecurityUnderAContextPath() throws Exception {
        check(call("GET", "/api/batches", null, null, null), 401);
        check(call("GET", "/api/batches", token("recon:write"), null, null), 403);
        check(call("GET", "/api/batches", token("recon:read"), null, null), 200);
        // Container decoding must not bypass the application response policy.
        check(call("GET", "/%61pi/batches", token("recon:read"), null, null), 200);
        var health = call("GET", "/actuator/health", null, null, null);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(json.readTree(health.body()).get("status").asText()).isEqualTo("UP");
    }

    @Test void coversWritesReplayAndMvcErrorsUnderAContextPath() throws Exception {
        String auth = token("recon:read recon:write");
        String key = "context-" + UUID.randomUUID();
        check(call("POST", "/api/batches", auth, "{}", key), 400);
        String batch = """
            {"businessDate":"2026-11-11","currency":"USD",
             "leftRecords":[{"recordId":"left","reference":"CTX-1","amount":"10.00"}],
             "rightRecords":[]}
            """;
        var created = call("POST", "/api/batches", auth, batch, key);
        check(created, 201);
        String id = json.readTree(created.body()).get("id").asText();
        check(call("GET", "/api/batches/" + id, auth, null, null), 200);
        var replay = call("POST", "/api/batches", auth, batch, key);
        check(replay, 200);
        assertThat(json.readTree(replay.body()).get("id").asText()).isEqualTo(id);
        check(call("GET", "/api/batches/missing", auth, null, null), 404);
        check(call("POST", "/api/batches", auth, batch.replace("10.00", "11.00"), key), 409);
    }

    private void check(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
        assertThat(response.headers().firstValue("X-Request-ID")).isPresent();
    }
    private HttpResponse<String> call(String method, String path, String auth, String body, String key)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ledger" + path))
            .timeout(Duration.ofSeconds(10));
        if (auth != null) request.header("Authorization", auth);
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() :
            HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String token(String scope) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).subject("context-reviewer")
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .claim("scope", scope).claim("tenant_id", "context-test").build();
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        token.sign(new RSASSASigner(KEY.getPrivate()));
        return "Bearer " + token.serialize();
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
