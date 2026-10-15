package dev.harshaattili.reconledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
            String eventQuery = "SELECT sequence, result_id, actor, from_state, to_state, resulting_version, note, created_at FROM "
                + prefix + "review_event";
            var events = jdbc.queryForList(eventQuery);

            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
            assertThat(jdbc.queryForList(headerQuery)).isEqualTo(headers);
            assertThat(jdbc.queryForList("SELECT * FROM " + prefix + "source_record")).isEqualTo(sources);
            assertThat(jdbc.queryForList("SELECT * FROM " + prefix + "recon_result")).isEqualTo(results);
            assertThat(jdbc.queryForList(eventQuery)).isEqualTo(events);
            assertThat(jdbc.queryForObject("SELECT actor_source FROM " + prefix + "review_event", String.class))
                .isEqualTo("LEGACY_UNKNOWN");
            assertThat(jdbc.queryForObject("SELECT actor_issuer FROM " + prefix + "review_event", String.class)).isNull();
            assertThat(jdbc.queryForList("SELECT tenant_id FROM " + prefix + "recon_batch", String.class))
                .containsExactly("demo", "demo");
            String scopedInsert = "INSERT INTO " + prefix
                + "recon_batch(id, idempotency_key, fingerprint, business_date, currency, created_at, tenant_id)"
                + " VALUES (?, 'key-old-a', ?, ?, 'USD', '2026-10-11T12:00:00Z', ?)";
            jdbc.update(scopedInsert, "tenant-new", "b".repeat(64), java.sql.Date.valueOf("2026-10-11"), "tenant-a");
            assertThatThrownBy(() -> jdbc.update(scopedInsert, "tenant-duplicate", "b".repeat(64),
                java.sql.Date.valueOf("2026-10-11"), "tenant-a"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
            assertThatThrownBy(() -> jdbc.update(scopedInsert, "demo-duplicate", "b".repeat(64),
                java.sql.Date.valueOf("2026-10-11"), "demo"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
            var sequences = jdbc.queryForList("SELECT list_sequence FROM " + prefix + "recon_batch", Long.class);
            assertThat(sequences).hasSize(3).doesNotHaveDuplicates().allMatch(sequence -> sequence > 0);
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

    @Test void keepsPriorSecuredActorsUnknownAndConstrainsNewProvenance() {
        String schema = "PROVENANCE_" + UUID.randomUUID().toString().replace("-", "");
        String prefix = "\"" + schema + "\".";
        var jdbc = new JdbcTemplate(dataSource);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .target("4").load().migrate();
            jdbc.update("INSERT INTO " + prefix + "recon_batch(id,idempotency_key,fingerprint,business_date,currency,created_at,tenant_id)"
                + " VALUES ('batch','old-key',?,'2026-10-11','USD','2026-10-11T12:00:00Z','private-tenant')", "a".repeat(64));
            jdbc.update("INSERT INTO " + prefix + "recon_result VALUES ('result','batch','REF','MISSING_RIGHT','IN_REVIEW',1,NULL)");
            jdbc.update("INSERT INTO " + prefix + "review_event(result_id,actor,from_state,to_state,resulting_version,note,created_at)"
                + " VALUES ('result','verified-looking-name','OPEN','IN_REVIEW',1,'Retained history','2026-10-11T12:00:00Z')");
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
            assertThat(jdbc.queryForObject("SELECT actor FROM " + prefix + "review_event", String.class))
                .isEqualTo("verified-looking-name");
            assertThat(jdbc.queryForObject("SELECT actor_source FROM " + prefix + "review_event", String.class))
                .isEqualTo("LEGACY_UNKNOWN");
            assertThat(jdbc.queryForObject("SELECT actor_issuer FROM " + prefix + "review_event", String.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT tenant_id FROM " + prefix + "recon_batch", String.class))
                .isEqualTo("private-tenant");
            String update = "UPDATE " + prefix + "review_event SET actor_source = ?, actor_issuer = ?";
            for (String source : new String[] {"DEMO_LABEL", "LEGACY_UNKNOWN", "unsupported"}) {
                assertThatThrownBy(() -> jdbc.update(update, source, "https://forged.invalid"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            }
            assertThatThrownBy(() -> jdbc.update(update, "VERIFIED_JWT", null))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(update, "VERIFIED_JWT", " "))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(jdbc.update(update, "VERIFIED_JWT", "https://issuer.example.invalid" )).isEqualTo(1);
            assertThat(jdbc.update(update, "DEMO_LABEL", null)).isEqualTo(1);
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
        }
    }
}
