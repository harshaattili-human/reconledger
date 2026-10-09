package dev.harshaattili.reconledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/** Observe plan reuse without running EXPLAIN between requests in a statement's history. */
final class MixedPreparedPlanProbe {
    private static final List<Request> MIX = List.of(
        new Request("USD", 100), new Request("XTS", 100),
        new Request("USD", 50), new Request("XTS", 50),
        new Request("USD", 1), new Request("XTS", 1));

    private MixedPreparedPlanProbe() {}

    static List<Map<String, Object>> inspect(DataSource dataSource) {
        List<Map<String, Object>> observations = new ArrayList<>();
        try (var connection = dataSource.getConnection(); var control = connection.createStatement()) {
            String originalMode;
            try (var rows = control.executeQuery("SHOW plan_cache_mode")) {
                rows.next();
                originalMode = rows.getString(1);
            }
            assertThat(originalMode).isIn("auto", "force_custom_plan", "force_generic_plan");
            var expected = referencePages(connection);
            try {
                for (Request primer : List.of(new Request("XTS", 100),
                        new Request("USD", 100), new Request("XTS", 1))) {
                    for (String mode : List.of("auto", "force_custom_plan")) {
                        control.execute("SET plan_cache_mode = " + mode);
                        observations.add(history(connection, primer, mode, expected));
                    }
                }
            } finally {
                control.execute("SET plan_cache_mode = " + originalMode);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Could not characterize mixed prepared requests", error);
        }
        assertThat(observations).hasSize(6);
        return observations;
    }

    private static Map<Request, List<Long>> referencePages(Connection connection) throws SQLException {
        Map<Request, List<Long>> pages = new LinkedHashMap<>();
        for (String currency : List.of("XTS", "USD")) {
            List<Long> ordered = new ArrayList<>();
            try (var query = connection.prepareStatement(
                    "SELECT list_sequence FROM recon_batch WHERE currency = ? ORDER BY list_sequence DESC")) {
                query.setString(1, currency);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) ordered.add(rows.getLong(1));
                }
            }
            assertThat(ordered.size()).isGreaterThanOrEqualTo(101);
            for (int limit : List.of(1, 50, 100)) {
                pages.put(new Request(currency, limit), List.copyOf(ordered.subList(0, limit + 1)));
            }
        }
        return pages;
    }

    private static Map<String, Object> history(Connection connection, Request primer, String mode,
            Map<Request, List<Long>> expected) throws SQLException {
        String marker = "/* reconledger-mixed-" + primer.currency() + "-" + primer.limit() + "-" + mode + " */";
        String sql = marker + """
            SELECT id, list_sequence, business_date, currency, created_at
            FROM recon_batch WHERE 1 = 1 AND currency = ?
            ORDER BY list_sequence DESC LIMIT ?
            """;
        List<Request> requests = new ArrayList<>();
        for (int i = 0; i < 10; i++) requests.add(primer);
        for (int i = 0; i < 3; i++) requests.addAll(MIX);
        List<Map<String, Object>> executions = new ArrayList<>();
        String statementName = null;
        long previousCustom = 0;
        long previousGeneric = 0;
        try (var query = connection.prepareStatement(sql)) {
            for (int index = 0; index < requests.size(); index++) {
                Request request = requests.get(index);
                query.setString(1, request.currency());
                query.setInt(2, request.limit() + 1);
                List<Long> actual = new ArrayList<>();
                long started = System.nanoTime();
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        rows.getString("id");
                        actual.add(rows.getLong("list_sequence"));
                        rows.getDate("business_date");
                        rows.getString("currency");
                        rows.getString("created_at");
                    }
                }
                double elapsed = (System.nanoTime() - started) / 1_000_000.0;
                assertThat(actual).isEqualTo(expected.get(request));
                Map<String, Object> execution = new LinkedHashMap<>();
                execution.put("execution", index + 1);
                execution.put("phase", index < 10 ? "primer" : "mixed");
                execution.put("currency", request.currency());
                execution.put("pageLimit", request.limit());
                execution.put("milliseconds", elapsed);
                // Catalog reads inspect counters; unlike EXPLAIN EXECUTE they do not run the target.
                try (var catalog = connection.prepareStatement("""
                        SELECT name, parameter_types::text, custom_plans, generic_plans
                        FROM pg_prepared_statements WHERE starts_with(statement, ?)
                        """)) {
                    catalog.setString(1, marker);
                    try (var rows = catalog.executeQuery()) {
                        if (rows.next()) {
                            String name = rows.getString("name");
                            if (statementName == null) statementName = name;
                            assertThat(name).as("one named statement throughout the history").isEqualTo(statementName);
                            long custom = rows.getLong("custom_plans");
                            long generic = rows.getLong("generic_plans");
                            long customDelta = custom - previousCustom;
                            long genericDelta = generic - previousGeneric;
                            assertThat(customDelta).isBetween(0L, 1L);
                            assertThat(genericDelta).isBetween(0L, 1L);
                            assertThat(customDelta + genericDelta).isEqualTo(1L);
                            execution.put("planKind", customDelta == 1 ? "custom" : "generic");
                            execution.put("customPlans", custom);
                            execution.put("genericPlans", generic);
                            execution.put("parameterTypes", rows.getString("parameter_types"));
                            previousCustom = custom;
                            previousGeneric = generic;
                            assertThat(rows.next()).isFalse();
                        } else {
                            assertThat(statementName).as("a named statement must not disappear").isNull();
                            execution.put("planKind", "unnamed-unobserved");
                        }
                    }
                }
                executions.add(execution);
            }
            assertThat(statementName).matches("[A-Za-z_][A-Za-z0-9_]*");
            List<String> finalPlan = new ArrayList<>();
            // Only after all 28 requests: this extra execution cannot affect recorded history.
            try (var explain = connection.createStatement(); var rows = explain.executeQuery(
                    "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) EXECUTE \"" + statementName + "\"('XTS', 2)")) {
                while (rows.next()) finalPlan.add(rows.getString(1));
            }
            return Map.of("primerCurrency", primer.currency(), "primerLimit", primer.limit(),
                "mode", mode, "primerExecutions", 10, "mixedExecutions", 18,
                "executions", executions, "finalXtsOneRowPagePlan", finalPlan);
        }
    }

    private record Request(String currency, int limit) {}
}
