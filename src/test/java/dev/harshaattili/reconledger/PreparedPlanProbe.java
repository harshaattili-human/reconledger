package dev.harshaattili.reconledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/** Same currency query and bindings on one connection, with observable server plan counters. */
final class PreparedPlanProbe {
    private PreparedPlanProbe() {}

    static List<Map<String, Object>> inspect(DataSource dataSource) {
        List<Map<String, Object>> observations = new ArrayList<>();
        try (var connection = dataSource.getConnection(); var control = connection.createStatement()) {
            String originalMode;
            try (var setting = control.executeQuery("SHOW plan_cache_mode")) {
                setting.next();
                originalMode = setting.getString(1);
            }
            assertThat(originalMode).isIn("auto", "force_custom_plan", "force_generic_plan");
            try {
                for (String currency : List.of("XTS", "USD")) {
                    for (int pageLimit : List.of(1, 50, 100)) {
                        int fetchLimit = pageLimit + 1;
                        List<Long> expected = new ArrayList<>();
                        try (var reference = connection.prepareStatement(
                                "SELECT list_sequence FROM recon_batch WHERE currency = ? ORDER BY list_sequence DESC")) {
                            reference.setString(1, currency);
                            try (var rows = reference.executeQuery()) {
                                while (rows.next() && expected.size() < fetchLimit) expected.add(rows.getLong(1));
                            }
                        }
                        assertThat(expected).hasSize(fetchLimit);
                        for (String mode : List.of("auto", "force_custom_plan", "force_generic_plan")) {
                            control.execute("SET plan_cache_mode = " + mode);
                            String marker = "/* reconledger-plan-" + currency + "-" + pageLimit + "-" + mode + " */";
                            String sql = marker + """
                                SELECT id, list_sequence, business_date, currency, created_at
                                FROM recon_batch WHERE 1 = 1 AND currency = ?
                                ORDER BY list_sequence DESC LIMIT ?
                                """;
                            List<Double> samples = new ArrayList<>();
                            try (var query = connection.prepareStatement(sql)) {
                                for (int execution = 1; execution <= 20; execution++) {
                                    query.setString(1, currency);
                                    query.setInt(2, fetchLimit);
                                    long started = System.nanoTime();
                                    List<Long> actual = new ArrayList<>();
                                    try (var rows = query.executeQuery()) {
                                        while (rows.next()) {
                                            // Consume the same projected fields as the repository.
                                            rows.getString("id");
                                            actual.add(rows.getLong("list_sequence"));
                                            rows.getDate("business_date");
                                            rows.getString("currency");
                                            rows.getString("created_at");
                                        }
                                    }
                                    samples.add((System.nanoTime() - started) / 1_000_000.0);
                                    assertThat(actual).isEqualTo(expected);
                                }
                                Map<String, Object> result = new LinkedHashMap<>();
                                result.put("mode", mode);
                                result.put("currency", currency);
                                result.put("pageLimit", pageLimit);
                                result.put("rowsIncludingLookahead", fetchLimit);
                                result.put("executions", 20);
                                result.put("executionMillisecondsInOrder", samples);
                                String name;
                                // The marker is fixed test code; no input values or credentials are read.
                                try (var prepared = control.executeQuery(
                                        "SELECT name, parameter_types::text, generic_plans, custom_plans "
                                        + "FROM pg_prepared_statements WHERE statement LIKE '" + marker + "%'")) {
                                    assertThat(prepared.next()).as("driver created a named prepared statement").isTrue();
                                    name = prepared.getString("name");
                                    result.put("parameterTypes", prepared.getString("parameter_types"));
                                    result.put("genericPlansBeforeExplain", prepared.getLong("generic_plans"));
                                    result.put("customPlansBeforeExplain", prepared.getLong("custom_plans"));
                                    assertThat(prepared.next()).isFalse();
                                }
                                assertThat(name).matches("[A-Za-z_][A-Za-z0-9_]*");
                                List<String> plan = new ArrayList<>();
                                try (var lines = control.executeQuery(
                                        "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) EXECUTE \"" + name + "\"('" + currency + "', " + fetchLimit + ")")) {
                                    while (lines.next()) plan.add(lines.getString(1));
                                }
                                result.put("planAfterExecutions", plan);
                                observations.add(result);
                            }
                        }
                    }
                }
                assertThat(observations).hasSize(18);
            } finally {
                // Hikari reuses physical connections; do not leak the diagnostic setting.
                control.execute("SET plan_cache_mode = " + originalMode);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Could not characterize prepared batch browsing", error);
        }
        return observations;
    }
}
