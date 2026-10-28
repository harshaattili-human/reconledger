package dev.harshaattili.reconledger;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Shared-buffer, ordinary-table experiment; all clone DDL/data roll back. */
@SpringBootTest
@ActiveProfiles("test")
class TenantRuntimeStatisticsTest {
    private static final int ROWS = 40_000, WARMUPS = 3, SAMPLES = 10;
    private static final List<String> CONFIGS = List.of("baseline", "index", "index-and-mcv", "mcv");
    @Autowired DataSource source;
    @Autowired ObjectMapper json;

    record Header(String id, long sequence, String tenant, String currency, String date, String created) {}

    @Test void comparesFreshOrdinaryClonesWithCounterbalancedConfigurations() throws Exception {
        Path output = Path.of("target/tenant-runtime-statistics.json");
        Files.deleteIfExists(output);
        var report = new LinkedHashMap<String, Object>();
        var trials = new ArrayList<Map<String, Object>>();
        report.put("status", "incomplete");
        report.put("trials", trials);
        try (var c = source.getConnection()) {
            var m = c.getMetaData();
            report.put("environment", Map.of("database", m.getDatabaseProductName(),
                "version", m.getDatabaseProductVersion(), "driver", m.getDriverVersion(),
                "java", System.getProperty("java.version"), "os", System.getProperty("os.name"),
                "architecture", System.getProperty("os.arch")));
            if (!m.getDatabaseProductName().equals("PostgreSQL")) {
                report.put("status", "not applicable: PostgreSQL ordinary-table statistics experiment");
                return;
            }
            report.put("protocol", Map.of("rowsPerClone", ROWS, "configs", CONFIGS,
                "repeats", 4, "tenantCounts", List.of(3, 200), "allocations", List.of("blocked", "shuffled"),
                "shuffle", "ORDER BY md5('20261028:' || logical_ordinal)",
                "warmupsPerPage", WARMUPS, "timedCallsPerPage", SAMPLES, "incrementalRows", 1000));
            report.put("scope", "AI-assisted synthetic header-only ordinary logged clones in rolled-back transactions; not repository/HTTP/load/commit latency");
            report.put("settings", strings(c, "SELECT name || '=' || setting FROM pg_settings WHERE name IN "
                + "('default_statistics_target','random_page_cost','effective_cache_size','shared_buffers',"
                + "'work_mem','plan_cache_mode','max_parallel_workers_per_gather','jit') ORDER BY name"));
            var original = applicationState(c);
            for (int tenants : List.of(3, 200)) {
                for (String allocation : List.of("blocked", "shuffled")) {
                    for (int repeat = 0; repeat < 4; repeat++) {
                        for (int position = 0; position < 4; position++) {
                            String config = CONFIGS.get((repeat + position) % CONFIGS.size());
                            var trial = trial(c, tenants, allocation, config);
                            trial.put("repeat", repeat);
                            trial.put("position", position);
                            trials.add(trial);
                        }
                    }
                }
            }
            assertThat(applicationState(c)).as("clones leave application rows/indexes/sequence untouched").isEqualTo(original);
            report.put("applicationStateUnchanged", true);
            report.put("status", "completed");
        } finally {
            Files.createDirectories(output.getParent());
            json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        }
    }

    private Map<String, Object> trial(Connection c, int tenants, String allocation, String config) throws Exception {
        assertThat(c.getAutoCommit()).isTrue();
        String table = "recon_stats_" + UUID.randomUUID().toString().replace("-", "");
        c.setAutoCommit(false);
        try {
            execute(c, "CREATE TABLE " + table + " (LIKE recon_batch INCLUDING ALL)");
            execute(c, "ALTER TABLE " + table + " SET (autovacuum_enabled=false)");
            assertThat(strings(c, "SELECT relpersistence::text FROM pg_class WHERE oid='" + table + "'::regclass"))
                .containsExactly("p");
            String tenant = "CASE WHEN n<=200 THEN 'probe-small' WHEN n<=20200 THEN 'probe-large' "
                + (tenants == 3 ? "ELSE 'probe-foreign' END" : "ELSE 'probe-foreign-' || ((n-20201)/100)::text END");
            String currency = "CASE WHEN n>400 AND n<=20200 THEN 'USD' ELSE 'XTS' END";
            String order = allocation.equals("blocked") ? "n" : "md5('20261028:' || n::text), n";
            long start = System.nanoTime();
            execute(c, "INSERT INTO " + table
                + "(id,idempotency_key,fingerprint,business_date,currency,created_at,tenant_id) SELECT "
                + "md5(n::text)::uuid::text,md5(n::text)::uuid::text,repeat('0',64),DATE '2092-01-01',"
                + currency + ",'2026-10-28T00:00:00Z'," + tenant
                + " FROM generate_series(1," + ROWS + ") n ORDER BY " + order);
            var result = new LinkedHashMap<String, Object>();
            result.put("tenants", tenants); result.put("allocation", allocation); result.put("configuration", config);
            result.put("initialLoadMs", elapsed(start));
            result.put("clonedIndexes", strings(c, "SELECT pg_get_indexdef(indexrelid) FROM pg_index WHERE indrelid='"
                + table + "'::regclass ORDER BY indexrelid"));
            if (config.contains("index")) {
                start = System.nanoTime();
                execute(c, "CREATE INDEX " + table + "_trial ON " + table + "(tenant_id,currency,list_sequence)");
                result.put("indexBuildMs", elapsed(start));
                result.put("trialIndexBytes", strings(c, "SELECT pg_relation_size('" + table + "_trial')::text"));
            }
            if (config.contains("mcv")) execute(c, "CREATE STATISTICS " + table
                + "_mcv (mcv) ON tenant_id,currency FROM " + table);
            start = System.nanoTime(); execute(c, "ANALYZE " + table); result.put("analyzeMs", elapsed(start));
            result.put("tableAndIndexBytes", strings(c, "SELECT pg_total_relation_size('" + table + "')::text"));
            if (config.contains("mcv")) {
                result.put("mcvBytes", strings(c, "SELECT pg_column_size(d.stxdmcv)::text FROM pg_statistic_ext e "
                    + "JOIN pg_statistic_ext_data d ON e.oid=d.stxoid WHERE e.stxrelid='" + table + "'::regclass"));
                result.put("mcvItems", strings(c, "SELECT row_to_json(m)::text FROM pg_statistic_ext e "
                    + "JOIN pg_statistic_ext_data d ON e.oid=d.stxoid CROSS JOIN LATERAL pg_mcv_list_items(d.stxdmcv) m "
                    + "WHERE e.stxrelid='" + table + "'::regclass"));
            }
            List<Header> all;
            try (var s = c.prepareStatement("SELECT id,list_sequence,tenant_id,currency,business_date,created_at FROM "
                    + table + " ORDER BY list_sequence DESC")) { all = headers(s); }
            assertThat(all).hasSize(ROWS);
            assertThat(all.stream().map(Header::tenant).distinct().count()).isEqualTo(tenants);
            var pages = new ArrayList<Map<String, Object>>();
            for (var filter : List.of(new String[]{"probe-large", "XTS", "200"},
                    new String[]{"probe-large", "USD", "19800"}, new String[]{"probe-small", "XTS", "200"})) {
                // Independent reference filters the complete clone in Java, not the timed SQL.
                var owned = all.stream().filter(r -> r.tenant().equals(filter[0]) && r.currency().equals(filter[1])).toList();
                assertThat(owned).hasSize(Integer.parseInt(filter[2]));
                pages.add(page(c, table, filter[0], filter[1], null, owned));
                pages.add(page(c, table, filter[0], filter[1], owned.get(99).sequence(), owned.subList(100, owned.size())));
            }
            result.put("pages", pages);
            start = System.nanoTime();
            execute(c, "INSERT INTO " + table
                + "(id,idempotency_key,fingerprint,business_date,currency,created_at,tenant_id) SELECT "
                + "md5(n::text)::uuid::text,md5(n::text)::uuid::text,repeat('0',64),DATE '2092-01-01',"
                + "'USD','2026-10-28T00:00:00Z','probe-large' FROM generate_series(40001,41000) n ORDER BY n");
            result.put("insert1000Ms", elapsed(start));
            start = System.nanoTime(); execute(c, "ANALYZE " + table); result.put("postInsertAnalyzeMs", elapsed(start));
            assertThat(strings(c, "SELECT count(*)::text FROM " + table)).containsExactly("41000");
            result.put("postInsertTableAndIndexBytes", strings(c, "SELECT pg_total_relation_size('" + table + "')::text"));
            return result;
        } finally {
            try { c.rollback(); } finally { c.setAutoCommit(true); }
            assertThat(strings(c, "SELECT (to_regclass('" + table + "') IS NULL)::text"))
                .as("rollback removes ordinary clone and dependent objects").containsExactly("true");
        }
    }

    private Map<String, Object> page(Connection c, String table, String tenant, String currency,
                                     Long cursor, List<Header> remaining) throws Exception {
        // Match the runtime projection, predicate order, binding and statement policy.
        String sql = "SELECT id,list_sequence,business_date,currency,created_at FROM " + table
            + " WHERE tenant_id=?" + (cursor == null ? "" : " AND list_sequence<?")
            + " AND currency=? ORDER BY list_sequence DESC LIMIT ?";
        var expected = remaining.subList(0, Math.min(101, remaining.size()));
        var samples = new ArrayList<Double>();
        try (var s = c.prepareStatement(sql)) {
            bind(s, tenant, currency, cursor);
            for (int i = 0; i < WARMUPS + SAMPLES; i++) {
                long start = System.nanoTime();
                var actual = new ArrayList<Header>();
                try (var rs = s.executeQuery()) {
                    while (rs.next()) actual.add(new Header(rs.getString(1), rs.getLong(2), tenant,
                        rs.getString(4).trim(), rs.getDate(3).toString(), rs.getString(5)));
                }
                double ms = elapsed(start);
                assertThat(actual).isEqualTo(expected);
                if (i >= WARMUPS) samples.add(ms);
            }
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("tenant", tenant); result.put("currency", currency);
        result.put("page", cursor == null ? "first" : "continuation"); result.put("remaining", remaining.size());
        result.put("sql", sql); result.put("orderedMs", samples);
        var sorted = samples.stream().sorted().toList();
        result.put("p50Ms", sorted.get(4)); result.put("p95Ms", sorted.get(9));
        try (var s = c.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql)) {
            bind(s, tenant, currency, cursor);
            try (var rs = s.executeQuery()) { assertThat(rs.next()).isTrue(); result.put("separatePlan", json.readTree(rs.getString(1))); }
        }
        return result;
    }

    private static void bind(PreparedStatement s, String tenant, String currency, Long cursor) throws Exception {
        BatchBrowseStatementPolicy.apply(s);
        int n = 1; s.setObject(n++, tenant); if (cursor != null) s.setObject(n++, cursor);
        s.setObject(n++, currency); s.setObject(n, 101);
    }

    private static List<Header> headers(PreparedStatement s) throws Exception {
        var result = new ArrayList<Header>();
        try (var rs = s.executeQuery()) {
            while (rs.next()) result.add(new Header(rs.getString(1), rs.getLong(2), rs.getString(3).trim(),
                rs.getString(4).trim(), rs.getDate(5).toString(), rs.getString(6)));
        }
        return result;
    }
    private static Map<String, Object> applicationState(Connection c) throws Exception {
        String sequence = strings(c, "SELECT pg_get_serial_sequence('recon_batch','list_sequence')").get(0);
        return Map.of("rows", strings(c, "SELECT count(*)::text FROM recon_batch"),
            "indexes", strings(c, "SELECT pg_get_indexdef(indexrelid) FROM pg_index WHERE indrelid='recon_batch'::regclass ORDER BY indexrelid"),
            "sequence", strings(c, "SELECT last_value::text || ':' || is_called::text FROM " + sequence));
    }
    private static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
    private static void execute(Connection c, String sql) throws Exception { try (var s = c.createStatement()) { s.execute(sql); } }
    private static List<String> strings(Connection c, String sql) throws Exception {
        var values = new ArrayList<String>();
        try (var s = c.createStatement(); var rs = s.executeQuery(sql)) { while (rs.next()) values.add(rs.getString(1)); }
        return values;
    }
}
