package dev.harshaattili.reconledger;

import static dev.harshaattili.reconledger.Model.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class QueryCharacterizationTest {
    private static final int HISTORY_PER_RESULT = 10_000;
    private static final int PAGE_LIMIT = 200;

    @Autowired ReconciliationService service;
    @Autowired LedgerRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @Test void recordsBoundedQueryBehaviorAtDocumentedLimits() throws Exception {
        var environment = databaseEnvironment();
        var batch = createMaximumBatch();
        var batchPlan = new LinkedHashMap<String, Object>();
        batchPlan.put("sourceRecords", explain(
            "SELECT source_side, record_id, reference, amount FROM source_record WHERE batch_id = ?", batch.id()));
        batchPlan.put("results", explain("SELECT * FROM recon_result WHERE batch_id = ?", batch.id()));
        var batchMeasurement = measure(() -> repository.getBatch(batch.id()), 5, 30);
        var loaded = repository.getBatch(batch.id());
        assertThat(loaded.results()).hasSize(500);
        assertThat(loaded.results()).allSatisfy(result -> {
            assertThat(result.leftRecords()).hasSize(1);
            assertThat(result.rightRecords()).hasSize(1);
        });

        var audit = createLongHistories();
        long midpoint = sequenceAtVersion(audit.targetResultId(), HISTORY_PER_RESULT / 2);
        var page = repository.events(audit.targetResultId(), midpoint, PAGE_LIMIT);
        assertThat(page.events()).hasSize(PAGE_LIMIT);
        assertThat(page.events().get(0).resultingVersion()).isEqualTo(HISTORY_PER_RESULT / 2 + 1);
        assertThat(page.nextAfterSequence()).isEqualTo(page.events().get(PAGE_LIMIT - 1).sequence());
        long finalCursor = sequenceAtVersion(audit.targetResultId(), HISTORY_PER_RESULT - PAGE_LIMIT);
        var finalPage = repository.events(audit.targetResultId(), finalCursor, PAGE_LIMIT);
        assertThat(finalPage.events()).hasSize(PAGE_LIMIT);
        assertThat(finalPage.nextAfterSequence()).isNull();

        var auditPlan = explain("""
            SELECT * FROM review_event
            WHERE result_id = ? AND sequence > ? ORDER BY sequence LIMIT ?
            """, audit.targetResultId(), midpoint, PAGE_LIMIT + 1);
        assertIndexedAuditPlan(auditPlan, environment.get("product").toString());
        var auditMeasurement = measure(
            () -> repository.events(audit.targetResultId(), midpoint, PAGE_LIMIT), 5, 40);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scope", "synthetic query characterization; not a load test or service-level objective");
        report.put("database", environment);
        report.put("maximumBatch", Map.of(
            "leftRecords", 500,
            "rightRecords", 500,
            "results", 500,
            "serializedResponseBytes", json.writeValueAsBytes(loaded).length,
            "warmReadMilliseconds", batchMeasurement,
            "plans", batchPlan));
        report.put("auditHistory", Map.of(
            "resultsPopulated", audit.resultIds().size(),
            "eventsPerResult", HISTORY_PER_RESULT,
            "totalFixtureEvents", HISTORY_PER_RESULT * audit.resultIds().size(),
            "cursorVersion", HISTORY_PER_RESULT / 2,
            "pageLimit", PAGE_LIMIT,
            "rowsReturned", page.events().size(),
            "warmReadMilliseconds", auditMeasurement,
            "plan", auditPlan));
        Path output = Path.of("target", "query-characterization.json");
        Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println("Query characterization written to " + output);
    }

    private BatchView createMaximumBatch() {
        var rows = IntStream.range(0, 500)
            .mapToObj(index -> new LedgerRecord("record-" + index, "REF-" + index, new BigDecimal("1.01")))
            .toList();
        var input = new BatchInput(LocalDate.of(2026, 10, 6), "USD", rows, rows);
        return service.create("query-batch-" + UUID.randomUUID(), input).batch();
    }

    private AuditFixture createLongHistories() {
        var left = IntStream.range(0, 3)
            .mapToObj(index -> new LedgerRecord("left-" + index, "AUDIT-" + index, BigDecimal.ONE)).toList();
        var right = IntStream.range(0, 3)
            .mapToObj(index -> new LedgerRecord("right-" + index, "AUDIT-" + index,
                new BigDecimal("2.00"))).toList();
        var batch = service.create("query-audit-" + UUID.randomUUID(),
            new BatchInput(LocalDate.of(2026, 10, 6), "USD", left, right)).batch();
        var resultIds = batch.results().stream().map(ResultView::id).toList();
        List<Object[]> rows = new ArrayList<>(HISTORY_PER_RESULT * resultIds.size());
        for (int version = 1; version <= HISTORY_PER_RESULT; version++) {
            String from = version % 2 == 1 ? "OPEN" : "IN_REVIEW";
            String to = version % 2 == 1 ? "IN_REVIEW" : "OPEN";
            for (String resultId : resultIds) {
                rows.add(new Object[] {resultId, "query-fixture", from, to, version,
                    "Synthetic query-plan fixture " + version, "2026-10-06T12:00:00Z"});
            }
        }
        jdbc.batchUpdate("""
            INSERT INTO review_event(result_id, actor, from_state, to_state, resulting_version, note, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, rows);
        for (String resultId : resultIds) {
            jdbc.update("UPDATE recon_result SET review_state = 'OPEN', version = ? WHERE id = ?",
                HISTORY_PER_RESULT, resultId);
        }
        jdbc.execute(databaseEnvironment().get("product").equals("PostgreSQL")
            ? "ANALYZE review_event" : "ANALYZE TABLE review_event");
        return new AuditFixture(resultIds.get(0), resultIds);
    }

    private long sequenceAtVersion(String resultId, int version) {
        return jdbc.queryForObject(
            "SELECT sequence FROM review_event WHERE result_id = ? AND resulting_version = ?",
            Long.class, resultId, version);
    }

    private List<String> explain(String query, Object... arguments) {
        String database = databaseEnvironment().get("product").toString();
        String prefix = database.equals("PostgreSQL")
            ? "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " : "EXPLAIN ANALYZE ";
        return jdbc.query(prefix + query, (result, row) -> result.getString(1), arguments);
    }

    private void assertIndexedAuditPlan(List<String> plan, String database) {
        String text = String.join("\n", plan).toLowerCase();
        if (database.equals("PostgreSQL")) {
            assertThat(text).contains("index scan");
            assertThat(text.contains("review_event_result") || text.contains("review_event_pkey"))
                .as("PostgreSQL should use either the result/sequence index or ordered primary key")
                .isTrue();
            return;
        }
        assertThat(text).contains("review_event_result");
    }

    private Map<String, Object> databaseEnvironment() {
        try (var connection = jdbc.getDataSource().getConnection()) {
            var metadata = connection.getMetaData();
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("product", metadata.getDatabaseProductName());
            values.put("version", metadata.getDatabaseProductVersion());
            values.put("driver", metadata.getDriverName() + " " + metadata.getDriverVersion());
            values.put("java", System.getProperty("java.version"));
            values.put("architecture", System.getProperty("os.arch"));
            return values;
        } catch (SQLException error) {
            throw new IllegalStateException("Could not read database metadata", error);
        }
    }

    private Map<String, Object> measure(Supplier<?> query, int warmups, int samples) {
        for (int i = 0; i < warmups; i++) query.get();
        List<Double> milliseconds = new ArrayList<>();
        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            query.get();
            milliseconds.add((System.nanoTime() - start) / 1_000_000.0);
        }
        milliseconds.sort(Comparator.naturalOrder());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("samples", samples);
        values.put("median", rounded(percentile(milliseconds, 0.50)));
        values.put("p95", rounded(percentile(milliseconds, 0.95)));
        values.put("minimum", rounded(milliseconds.get(0)));
        values.put("maximum", rounded(milliseconds.get(milliseconds.size() - 1)));
        return values;
    }

    private double percentile(List<Double> values, double percentile) {
        return values.get(Math.max(0, (int) Math.ceil(values.size() * percentile) - 1));
    }

    private double rounded(double value) {
        return Math.round(value * 1_000.0) / 1_000.0;
    }

    private record AuditFixture(String targetResultId, List<String> resultIds) {}
}
