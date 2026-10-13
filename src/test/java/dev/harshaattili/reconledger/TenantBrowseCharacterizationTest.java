package dev.harshaattili.reconledger;

import static dev.harshaattili.reconledger.Model.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class TenantBrowseCharacterizationTest {
    private static final int LIMIT = 100;
    private static final LocalDate RARE_DATE = LocalDate.of(2091, 1, 1);
    private static final String PROJECTION =
        "SELECT id, list_sequence, business_date, currency, created_at FROM recon_batch WHERE tenant_id = ?";

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @Test void recordsTenantFilteredPagesWithUnevenOwnership() throws Exception {
        Path output = Path.of("target", "tenant-browse-characterization.json");
        Files.deleteIfExists(output);
        String prefix = "probe-" + UUID.randomUUID();
        String small = prefix + "-small";
        String large = prefix + "-large";
        String foreign = prefix + "-foreign";
        var originalContext = SecurityContextHolder.getContext();
        var environment = new MockEnvironment();
        environment.setActiveProfiles("secured");
        var repository = new LedgerRepository(jdbc, new TenantAccess(environment));
        List<Object[]> rows = new ArrayList<>();
        addHeaders(rows, small, 200, "XTS", RARE_DATE);
        addHeaders(rows, large, 200, "XTS", RARE_DATE);
        addHeaders(rows, large, 19_800, "USD", RARE_DATE.plusDays(1));
        addHeaders(rows, foreign, 19_800, "XTS", RARE_DATE.plusDays(1));
        try {
            jdbc.batchUpdate("""
                INSERT INTO recon_batch(id, idempotency_key, fingerprint, business_date, currency, created_at, tenant_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, rows);
            var database = databaseEnvironment();
            jdbc.execute(database.get("product").equals("PostgreSQL")
                ? "ANALYZE recon_batch" : "ANALYZE TABLE recon_batch");
            var observations = new ArrayList<Map<String, Object>>();
            for (String tenant : List.of(small, large)) {
                selectTenant(tenant);
                // Read only this fixture's headers once; expected pages are filtered in Java.
                var owned = jdbc.query("SELECT id, list_sequence, business_date, currency, created_at"
                    + " FROM recon_batch WHERE tenant_id = ? ORDER BY list_sequence DESC",
                    (rs, n) -> new BatchSummary(rs.getString("id"), rs.getLong("list_sequence"),
                        rs.getDate("business_date").toLocalDate(), rs.getString("currency"),
                        rs.getString("created_at")), tenant);
                assertThat(owned).hasSize(tenant.equals(small) ? 200 : 20_000);
                for (String filter : List.of("all", "date", "currency", "combined", "no-match")) {
                    LocalDate date = List.of("date", "combined").contains(filter) ? RARE_DATE : null;
                    String currency = filter.equals("no-match") ? "ZZZ"
                        : List.of("currency", "combined").contains(filter) ? "XTS" : null;
                    var expected = owned.stream().filter(row -> date == null || row.businessDate().equals(date))
                        .filter(row -> currency == null || row.currency().equals(currency)).toList();
                    assertThat(expected).hasSize(filter.equals("no-match") ? 0
                        : filter.equals("all") ? owned.size() : 200);
                    var observation = new LinkedHashMap<String, Object>();
                    observation.put("tenant", tenant.equals(small) ? "small" : "large");
                    observation.put("filter", filter);
                    observation.put("matchingHeaders", expected.size());
                    observation.put("first", inspectPage(repository, tenant, null, date, currency, expected, database));
                    if (expected.size() > LIMIT) {
                        long cursor = expected.get(LIMIT - 1).sequence();
                        observation.put("continuation", inspectPage(repository, tenant, cursor, date, currency,
                            expected.subList(LIMIT, expected.size()), database));
                    }
                    observations.add(observation);
                }
            }
            String empty = prefix + "-empty";
            selectTenant(empty);
            observations.add(Map.of("tenant", "empty", "filter", "all", "matchingHeaders", 0,
                "first", inspectPage(repository, empty, null, null, null, List.of(), database)));
            var report = new LinkedHashMap<String, Object>();
            report.put("scope", "AI-assisted synthetic header-only query fixture; not valid complete batches, ingestion or load evidence");
            report.put("database", database);
            report.put("fixtureHeaders", rows.size());
            report.put("tableHeadersAtRead", jdbc.queryForObject("SELECT COUNT(*) FROM recon_batch", Long.class));
            report.put("allocationOrder", List.of("small: 200 XTS/rare date", "large: 200 XTS/rare date",
                "large: 19800 USD/common date", "foreign: 19800 XTS/common date"));
            report.put("pageLimit", LIMIT);
            report.put("warmupsPerPage", 5);
            report.put("timedCallsPerPage", 30);
            report.put("observations", observations);
            Files.createDirectories(output.getParent());
            json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        } finally {
            SecurityContextHolder.setContext(originalContext);
            jdbc.batchUpdate("DELETE FROM recon_batch WHERE id = ?",
                rows.stream().map(row -> new Object[] {row[0]}).toList());
        }
    }

    private void addHeaders(List<Object[]> rows, String tenant, int count, String currency, LocalDate date) {
        for (int index = 0; index < count; index++) {
            String id = UUID.randomUUID().toString();
            rows.add(new Object[] {id, id, "0".repeat(64), java.sql.Date.valueOf(date), currency,
                "2026-10-13T12:00:00Z", tenant});
        }
    }

    private void selectTenant(String tenant) {
        // Trusted internal context only; signature and membership checks belong to secured HTTP tests.
        var token = Jwt.withTokenValue("synthetic-query-context").header("alg", "RS256")
            .subject("query-probe").claim("tenant_id", tenant).build();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(token, List.of()));
        SecurityContextHolder.setContext(context);
    }

    private Map<String, Object> inspectPage(LedgerRepository repository, String tenant, Long cursor,
            LocalDate date, String currency, List<BatchSummary> remaining, Map<String, Object> database) {
        var expected = remaining.subList(0, Math.min(LIMIT, remaining.size()));
        Long next = remaining.size() > LIMIT ? expected.get(LIMIT - 1).sequence() : null;
        List<Double> samples = new ArrayList<>();
        for (int call = 0; call < 35; call++) {
            long start = System.nanoTime();
            var page = repository.batches(cursor, LIMIT, date, currency);
            double elapsed = (System.nanoTime() - start) / 1_000_000.0;
            assertThat(page.batches()).isEqualTo(expected);
            assertThat(page.nextBeforeSequence()).isEqualTo(next);
            if (call >= 5) samples.add(elapsed);
        }
        // Match runtime projection, predicate order, setObject bindings and statement policy.
        String sql = PROJECTION;
        List<Object> arguments = new ArrayList<>();
        arguments.add(tenant);
        if (cursor != null) { sql += " AND list_sequence < ?"; arguments.add(cursor); }
        if (date != null) { sql += " AND business_date = ?"; arguments.add(java.sql.Date.valueOf(date)); }
        if (currency != null) { sql += " AND currency = ?"; arguments.add(currency); }
        sql += " ORDER BY list_sequence DESC LIMIT ?";
        arguments.add(LIMIT + 1);
        String explain = database.get("product").equals("PostgreSQL")
            ? "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " : "EXPLAIN ANALYZE ";
        var plan = jdbc.query(explain + sql, statement -> {
            BatchBrowseStatementPolicy.apply(statement);
            for (int i = 0; i < arguments.size(); i++) statement.setObject(i + 1, arguments.get(i));
        }, (rs, n) -> rs.getString(1));
        var sorted = samples.stream().sorted(Comparator.naturalOrder()).toList();
        var result = new LinkedHashMap<String, Object>();
        result.put("rowsReturned", expected.size());
        result.put("hasNext", next != null);
        result.put("sql", sql);
        result.put("orderedMilliseconds", samples);
        result.put("medianMilliseconds", sorted.get(14));
        result.put("p95Milliseconds", sorted.get(28));
        result.put("separateExplainExecution", plan);
        return result;
    }

    private Map<String, Object> databaseEnvironment() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            var metadata = connection.getMetaData();
            return Map.of("product", metadata.getDatabaseProductName(), "version", metadata.getDatabaseProductVersion(),
                "driver", metadata.getDriverName() + " " + metadata.getDriverVersion(),
                "java", System.getProperty("java.version"), "os", System.getProperty("os.name"),
                "architecture", System.getProperty("os.arch"));
        }
    }
}
