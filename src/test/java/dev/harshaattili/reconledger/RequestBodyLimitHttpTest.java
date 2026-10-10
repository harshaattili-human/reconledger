package dev.harshaattili.reconledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RequestBodyLimitHttpTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5)).build();
    private static final String BATCH = """
        {"businessDate":"2026-10-10","currency":"USD",
         "leftRecords":[{"recordId":"left-1","reference":"INV-1","amount":"10.00"}],
         "rightRecords":[]}
        """;

    @Test void acceptsTheExactByteLimitWithEitherFramingAndPreservesReplay() throws Exception {
        byte[] padded = padded(BATCH, RequestBodyLimitAdvice.MAX_BYTES);
        for (boolean chunked : List.of(false, true)) {
            String key = key();
            var created = post("/api/batches", padded, chunked, key, null);
            assertThat(created.statusCode()).isEqualTo(201);
            var replay = post("/api/batches", padded, chunked, key, null);
            assertThat(replay.statusCode()).isEqualTo(200);
            assertThat(body(replay).get("id")).isEqualTo(body(created).get("id"));
            assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        }
    }

    @Test void rejectsOneByteOverWithEitherFramingBeforeReservingTheKey() throws Exception {
        for (boolean chunked : List.of(false, true)) {
            String key = key();
            var rejected = post("/api/batches", padded(BATCH, RequestBodyLimitAdvice.MAX_BYTES + 1),
                chunked, key, null);
            problem(rejected, 413);
            assertThat(body(rejected).get("detail").asText()).contains("524288");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recon_batch WHERE idempotency_key = ?",
                Integer.class, key)).isZero();
            assertThat(post("/api/batches", bytes(BATCH), chunked, key, null).statusCode()).isEqualTo(201);
        }
    }

    @Test void rejectedReviewsLeaveVersionAndAuditUntouchedAndCanBeRetried() throws Exception {
        var created = post("/api/batches", bytes(BATCH), false, key(), null);
        String id = body(created).at("/results/0/id").asText();
        String review = """
            {"targetState":"IN_REVIEW","expectedVersion":0,"actor":"demo-reviewer","note":"Checking café evidence."}
            """;
        for (boolean chunked : List.of(false, true)) {
            problem(post("/api/results/" + id + "/reviews",
                padded(review, RequestBodyLimitAdvice.MAX_BYTES + 1), chunked, null, null), 413);
        }
        assertThat(body(get("/api/results/" + id)).get("version").asInt()).isZero();
        assertThat(body(get("/api/results/" + id + "/events")).get("events")).isEmpty();
        var accepted = post("/api/results/" + id + "/reviews", bytes(review), true, null, null);
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(body(accepted).get("version").asInt()).isEqualTo(1);
        assertThat(body(get("/api/results/" + id + "/events")).at("/events/0/note").asText())
            .isEqualTo("Checking café evidence.");
    }

    @Test void countsUtf8BytesAndRejectsCompressedBodies() throws Exception {
        String oversized = "{\"currency\":\"" + "é".repeat(RequestBodyLimitAdvice.MAX_BYTES / 2) + "\"}";
        assertThat(oversized.length()).isLessThan(RequestBodyLimitAdvice.MAX_BYTES);
        assertThat(bytes(oversized).length).isGreaterThan(RequestBodyLimitAdvice.MAX_BYTES);
        problem(post("/api/batches", bytes(oversized), true, key(), null), 413);
        var compressed = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(compressed)) { gzip.write(bytes(BATCH)); }
        problem(post("/api/batches", compressed.toByteArray(), false, key(), "gzip"), 415);
        assertThat(post("/api/batches", bytes(BATCH), false, key(), "identity").statusCode()).isEqualTo(201);
    }

    @Test void retainsJsonValidationAndAcceptsTheMaximumRecordFixture() throws Exception {
        problem(post("/api/batches", bytes("{broken"), true, key(), null), 400);
        problem(post("/api/batches", bytes(""), false, key(), null), 400);
        var rows = IntStream.range(0, 500).mapToObj(index -> Map.of(
            "recordId", String.format("%080d", index), "reference", String.format("%080d", index),
            "amount", "-999999999999.99")).toList();
        byte[] full = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of(
            "businessDate", "2026-10-10", "currency", "USD", "leftRecords", rows, "rightRecords", rows));
        assertThat(full.length).isLessThan(RequestBodyLimitAdvice.MAX_BYTES);
        var created = post("/api/batches", full, true, key(), null);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(body(created).at("/counts/MATCHED").asInt()).isEqualTo(500);
        var tooMany = new java.util.ArrayList<>(rows);
        tooMany.add(Map.of("recordId", "extra", "reference", "extra", "amount", "1.00"));
        problem(post("/api/batches", json.writeValueAsBytes(Map.of("businessDate", "2026-10-10",
            "currency", "USD", "leftRecords", tooMany, "rightRecords", List.of())), true, key(), null), 400);
    }

    private HttpResponse<String> post(String path, byte[] payload, boolean chunked,
                                      String key, String encoding) throws Exception {
        var publisher = chunked ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(payload))
            : HttpRequest.BodyPublishers.ofByteArray(payload);
        assertThat(publisher.contentLength()).isEqualTo(chunked ? -1L : payload.length);
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json").POST(publisher);
        if (key != null) request.header("Idempotency-Key", key);
        if (encoding != null) request.header("Content-Encoding", encoding);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private JsonNode body(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
    private String key() { return "body-limit-" + UUID.randomUUID(); }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private void problem(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(body(response).get("status").asInt()).isEqualTo(status);
    }

    private byte[] padded(String text, int length) {
        byte[] original = bytes(text);
        byte[] padded = Arrays.copyOf(original, length);
        Arrays.fill(padded, original.length, length, (byte) ' ');
        return padded;
    }
}
