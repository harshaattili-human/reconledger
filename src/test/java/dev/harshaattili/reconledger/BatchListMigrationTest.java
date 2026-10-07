package dev.harshaattili.reconledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class BatchListMigrationTest {
    @Autowired DataSource dataSource;

    @Test void upgradesExistingBatchesWithoutChangingTheirEvidence() {
        String schema = "MIGRATION_" + UUID.randomUUID().toString().replace("-", "");
        String prefix = "\"" + schema + "\".";
        var jdbc = new JdbcTemplate(dataSource);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .target("1").load().migrate();
            for (String id : new String[] {"old-a", "old-b"}) {
                jdbc.update("INSERT INTO " + prefix + "recon_batch VALUES (?, ?, ?, ?, ?, ?)",
                    id, "key-" + id, "a".repeat(64), java.sql.Date.valueOf("2026-10-03"), "USD", "2026-10-03T12:00:00Z");
            }
            jdbc.update("INSERT INTO " + prefix + "source_record VALUES ('old-a', 'LEFT', 'record', 'REF', 1.25)");
            jdbc.update("INSERT INTO " + prefix + "recon_result VALUES ('result', 'old-a', 'REF', 'MISSING_RIGHT', 'IN_REVIEW', 1, NULL)");
            jdbc.update("INSERT INTO " + prefix + "review_event(result_id, actor, from_state, to_state, resulting_version, note, created_at)"
                + " VALUES ('result', 'demo', 'OPEN', 'IN_REVIEW', 1, 'Synthetic migration evidence', '2026-10-03T12:00:00Z')");
            String headerQuery = "SELECT id, idempotency_key, fingerprint, business_date, currency, created_at FROM "
                + prefix + "recon_batch ORDER BY id";
            var headers = jdbc.queryForList(headerQuery);
            var sources = jdbc.queryForList("SELECT * FROM " + prefix + "source_record");
            var results = jdbc.queryForList("SELECT * FROM " + prefix + "recon_result");
            var events = jdbc.queryForList("SELECT * FROM " + prefix + "review_event");

            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
            assertThat(jdbc.queryForList(headerQuery)).isEqualTo(headers);
            assertThat(jdbc.queryForList("SELECT * FROM " + prefix + "source_record")).isEqualTo(sources);
            assertThat(jdbc.queryForList("SELECT * FROM " + prefix + "recon_result")).isEqualTo(results);
            assertThat(jdbc.queryForList("SELECT * FROM " + prefix + "review_event")).isEqualTo(events);
            var sequences = jdbc.queryForList("SELECT list_sequence FROM " + prefix + "recon_batch", Long.class);
            assertThat(sequences).hasSize(2).doesNotHaveDuplicates().allMatch(sequence -> sequence > 0);
            jdbc.update("INSERT INTO " + prefix + "recon_batch(id, idempotency_key, fingerprint, business_date, currency, created_at)"
                + " VALUES ('new', 'key-new', ?, ?, 'USD', '2026-10-07T12:00:00Z')",
                "b".repeat(64), java.sql.Date.valueOf("2026-10-07"));
            assertThat(jdbc.queryForObject("SELECT list_sequence FROM " + prefix + "recon_batch WHERE id = 'new'", Long.class))
                .isGreaterThan(sequences.stream().mapToLong(Long::longValue).max().orElseThrow());
        } finally {
            // Only the randomly named schema owned by this test is removed.
            jdbc.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
        }
    }
}
