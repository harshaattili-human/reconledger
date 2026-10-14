package dev.harshaattili.reconledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import javax.sql.DataSource;

/** Isolated plan experiment: no DDL or statistics changes on application tables. */
final class TenantStatisticsProbe {
    private static final String TABLE = "pg_temp.tenant_statistics_probe";
    private static final String PREFIX = "probe-00000000-0000-0000-0000-000000000000-";
    private static final int LIMIT = 100;
    private static final long SHUFFLE_SEED = 20261014L;

    private TenantStatisticsProbe() {}

    static void writeReport(DataSource source, ObjectMapper json) throws Exception {
        Path output = Path.of("target", "tenant-statistics.json");
        Files.deleteIfExists(output);
        var report = new LinkedHashMap<String, Object>();
        try (var connection = source.getConnection()) {
            var metadata = connection.getMetaData();
            report.put("database", Map.of("product", metadata.getDatabaseProductName(),
                "version", metadata.getDatabaseProductVersion(), "driver", metadata.getDriverVersion(),
                "java", System.getProperty("java.version"), "os", System.getProperty("os.name"),
                "architecture", System.getProperty("os.arch")));
            if (!metadata.getDatabaseProductName().equals("PostgreSQL")) {
                report.put("status", "not applicable: PostgreSQL extended-statistics experiment");
            } else {
                report.put("status", "completed");
                report.put("scope", "synthetic temporary header-table experiment, not repository or HTTP latency");
                report.put("fixtureRowsPerOrder", 40_000);
                report.put("shuffleSeed", SHUFFLE_SEED);
                report.put("pageLimit", LIMIT);
                report.put("warmupsPerPage", 5);
                report.put("samplesPerPage", 20);
                var orders = new ArrayList<Map<String, Object>>();
                for (String order : List.of("blocked", "shuffled")) {
                    orders.add(inspect(connection, order, json));
                    assertThat(strings(connection,
                        "SELECT (to_regclass('" + TABLE + "') IS NULL)::text"))
                        .as("transaction rollback removes the temporary fixture").containsExactly("true");
                }
                report.put("orders", orders);
            }
        }
        Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }

    private static Map<String, Object> inspect(Connection connection, String order, ObjectMapper json)
            throws Exception {
        boolean autoCommit = connection.getAutoCommit();
        assertThat(autoCommit).as("probe owns its transaction").isTrue();
        connection.setAutoCommit(false);
        try {
            execute(connection, "CREATE TEMP TABLE tenant_statistics_probe (LIKE recon_batch INCLUDING ALL) ON COMMIT DROP");
            var rows = new ArrayList<String[]>();
            add(rows, "small", "XTS", 200);
            add(rows, "large", "XTS", 200);
            add(rows, "large", "USD", 19_800);
            add(rows, "foreign", "XTS", 19_800);
            if (order.equals("shuffled")) Collections.shuffle(rows, new Random(SHUFFLE_SEED));
            try (var insert = connection.prepareStatement("INSERT INTO " + TABLE
                    + "(id, idempotency_key, fingerprint, business_date, currency, created_at, tenant_id)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                for (var row : rows) {
                    String id = UUID.randomUUID().toString();
                    insert.setString(1, id);
                    insert.setString(2, id);
                    insert.setString(3, "0".repeat(64));
                    insert.setDate(4, java.sql.Date.valueOf(row[1].equals("USD") || row[0].equals("foreign")
                        ? "2091-01-02" : "2091-01-01"));
                    insert.setString(5, row[1]);
                    insert.setString(6, "2026-10-14T12:00:00Z");
                    insert.setString(7, PREFIX + row[0]);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            var result = new LinkedHashMap<String, Object>();
            result.put("allocation", order);
            result.put("clonedIndexes", strings(connection,
                "SELECT pg_get_indexdef(indexrelid) FROM pg_index WHERE indrelid = '" + TABLE + "'::regclass ORDER BY indexrelid"));
            var configurations = new ArrayList<Map<String, Object>>();
            for (String configuration : List.of("baseline", "index", "index-and-mcv", "mcv")) {
                if (configuration.equals("index")) execute(connection,
                    "CREATE INDEX tenant_statistics_trial_index ON " + TABLE + "(tenant_id, currency, list_sequence)");
                if (configuration.equals("index-and-mcv")) execute(connection,
                    "CREATE STATISTICS pg_temp.tenant_statistics_trial_mcv (mcv) ON tenant_id, currency FROM " + TABLE);
                if (configuration.equals("mcv")) execute(connection, "DROP INDEX pg_temp.tenant_statistics_trial_index");
                long start = System.nanoTime();
                execute(connection, "ANALYZE " + TABLE);
                var observation = new LinkedHashMap<String, Object>();
                observation.put("configuration", configuration);
                observation.put("analyzeMilliseconds", (System.nanoTime() - start) / 1_000_000.0);
                observation.put("tableAndIndexBytes", strings(connection,
                    "SELECT pg_total_relation_size('" + TABLE + "'::regclass)::text"));
                if (configuration.contains("mcv")) observation.put("mcvItems", strings(connection,
                    "SELECT row_to_json(m)::text FROM pg_statistic_ext e JOIN pg_statistic_ext_data d"
                    + " ON d.stxoid=e.oid CROSS JOIN LATERAL pg_mcv_list_items(d.stxdmcv) m"
                    + " WHERE e.stxrelid='" + TABLE + "'::regclass"));
                var pages = new ArrayList<Map<String, Object>>();
                for (var request : List.of(new String[] {"large", "XTS", "200"},
                        new String[] {"large", "USD", "19800"}, new String[] {"small", "XTS", "200"})) {
                    var expected = expected(connection, request[0], request[1]);
                    assertThat(expected).hasSize(Integer.parseInt(request[2]));
                    pages.add(page(connection, request[0], request[1], null, expected, json));
                    pages.add(page(connection, request[0], request[1], expected.get(99).sequence(),
                        expected.subList(100, expected.size()), json));
                }
                observation.put("pages", pages);
                configurations.add(observation);
            }
            result.put("configurations", configurations);
            return result;
        } finally {
            try { connection.rollback(); }
            finally { connection.setAutoCommit(autoCommit); }
        }
    }

    private static void add(List<String[]> rows, String tenant, String currency, int count) {
        for (int i = 0; i < count; i++) rows.add(new String[] {tenant, currency});
    }

    private static List<Model.BatchSummary> expected(Connection connection, String tenant, String currency)
            throws SQLException {
        try (var statement = connection.prepareStatement("SELECT id, list_sequence, business_date, currency, created_at FROM "
                + TABLE + " WHERE tenant_id = ? AND currency = ? ORDER BY list_sequence DESC")) {
            statement.setObject(1, PREFIX + tenant);
            statement.setObject(2, currency);
            return summaries(statement);
        }
    }

    private static Map<String, Object> page(Connection connection, String tenant, String currency, Long cursor,
            List<Model.BatchSummary> remaining, ObjectMapper json) throws Exception {
        String sql = "SELECT id, list_sequence, business_date, currency, created_at FROM " + TABLE
            + " WHERE tenant_id = ?" + (cursor == null ? "" : " AND list_sequence < ?")
            + " AND currency = ? ORDER BY list_sequence DESC LIMIT ?";
        var expected = remaining.subList(0, Math.min(101, remaining.size()));
        var samples = new ArrayList<Double>();
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, tenant, currency, cursor);
            for (int i = 0; i < 25; i++) {
                long start = System.nanoTime();
                var actual = summaries(statement);
                double elapsed = (System.nanoTime() - start) / 1_000_000.0;
                assertThat(actual).isEqualTo(expected);
                if (i >= 5) samples.add(elapsed);
            }
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("tenant", tenant);
        result.put("currency", currency);
        result.put("page", cursor == null ? "first" : "continuation");
        result.put("remainingMatches", remaining.size());
        result.put("sql", sql);
        result.put("orderedMilliseconds", samples);
        var sorted = samples.stream().sorted().toList();
        result.put("medianMilliseconds", sorted.get(9));
        result.put("p95Milliseconds", sorted.get(18));
        try (var statement = connection.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql)) {
            bind(statement, tenant, currency, cursor);
            try (var rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                result.put("separateExplainExecution", json.readTree(rs.getString(1)));
            }
        }
        return result;
    }

    private static void bind(PreparedStatement statement, String tenant, String currency, Long cursor)
            throws SQLException {
        BatchBrowseStatementPolicy.apply(statement);
        int parameter = 1;
        statement.setObject(parameter++, PREFIX + tenant);
        if (cursor != null) statement.setObject(parameter++, cursor);
        statement.setObject(parameter++, currency);
        statement.setObject(parameter, LIMIT + 1);
    }

    private static List<Model.BatchSummary> summaries(PreparedStatement statement) throws SQLException {
        var rows = new ArrayList<Model.BatchSummary>();
        try (ResultSet rs = statement.executeQuery()) {
            while (rs.next()) rows.add(new Model.BatchSummary(rs.getString(1), rs.getLong(2),
                rs.getDate(3).toLocalDate(), rs.getString(4), rs.getString(5)));
        }
        return rows;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static List<String> strings(Connection connection, String sql) throws SQLException {
        var values = new ArrayList<String>();
        try (var statement = connection.createStatement(); var rs = statement.executeQuery(sql)) {
            while (rs.next()) values.add(rs.getString(1));
        }
        return values;
    }
}
