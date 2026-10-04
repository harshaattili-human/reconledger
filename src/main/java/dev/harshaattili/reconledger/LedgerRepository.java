package dev.harshaattili.reconledger;

import static dev.harshaattili.reconledger.Model.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerRepository {
    private final JdbcTemplate jdbc;

    public LedgerRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record ExistingBatch(String id, String fingerprint) {}
    public record StoredResult(String id, String batchId, String reference, Outcome outcome,
                               ReviewState state, int version, String resolutionNote) {}
    private record SourcedRecord(String side, LedgerRecord record) {}

    public Optional<ExistingBatch> findByKey(String key) {
        return jdbc.query("SELECT id, fingerprint FROM recon_batch WHERE idempotency_key = ?",
            (rs, n) -> new ExistingBatch(rs.getString("id"), rs.getString("fingerprint")), key).stream().findFirst();
    }

    public void insertBatch(String id, String key, String fingerprint, BatchInput input, String createdAt) {
        jdbc.update("""
            INSERT INTO recon_batch(id, idempotency_key, fingerprint, business_date, currency, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """, id, key, fingerprint, input.businessDate(), input.currency(), createdAt);
        insertRecords(id, "LEFT", input.leftRecords());
        insertRecords(id, "RIGHT", input.rightRecords());
    }

    private void insertRecords(String batchId, String side, List<LedgerRecord> records) {
        jdbc.batchUpdate("INSERT INTO source_record(batch_id, source_side, record_id, reference, amount) VALUES (?, ?, ?, ?, ?)",
            records, 100, (ps, record) -> {
                ps.setString(1, batchId);
                ps.setString(2, side);
                ps.setString(3, record.recordId());
                ps.setString(4, record.reference());
                ps.setBigDecimal(5, record.amount());
            });
    }

    public void insertResults(String batchId, List<Match> matches) {
        jdbc.batchUpdate("""
            INSERT INTO recon_result(id, batch_id, reference, outcome, review_state, version)
            VALUES (?, ?, ?, ?, ?, 0)
            """, matches, 100, (ps, match) -> {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, batchId);
                ps.setString(3, match.reference());
                ps.setString(4, match.outcome().name());
                ps.setString(5, match.outcome() == Outcome.MATCHED ? "NOT_REQUIRED" : "OPEN");
            });
    }

    public BatchView getBatch(String id) {
        var header = jdbc.query("SELECT business_date, currency, created_at FROM recon_batch WHERE id = ?",
            (rs, n) -> new BatchView(id, rs.getDate("business_date").toLocalDate(), rs.getString("currency"),
                rs.getString("created_at"), Map.of(), List.of()), id).stream().findFirst()
            .orElseThrow(() -> ApiException.notFound("Batch not found."));
        var sources = jdbc.query("SELECT source_side, record_id, reference, amount FROM source_record WHERE batch_id = ?",
            (rs, n) -> new SourcedRecord(rs.getString("source_side"),
                new LedgerRecord(rs.getString("record_id"), rs.getString("reference"), rs.getBigDecimal("amount"))), id);
        // Batches are bounded to 1,000 source rows. Sort here to match the engine's
        // case-sensitive order without depending on the database's locale/collation.
        sources.sort(Comparator.comparing(source -> source.record().recordId()));
        var left = byReference(sources, "LEFT");
        var right = byReference(sources, "RIGHT");
        var results = jdbc.query("SELECT * FROM recon_result WHERE batch_id = ?", (rs, n) -> {
            var stored = result(rs);
            return view(stored, left.getOrDefault(stored.reference(), List.of()), right.getOrDefault(stored.reference(), List.of()));
        }, id);
        results.sort(Comparator.comparing(ResultView::reference));
        Map<Outcome, Long> counts = new EnumMap<>(Outcome.class);
        for (Outcome outcome : Outcome.values()) counts.put(outcome, 0L);
        results.forEach(r -> counts.merge(r.outcome(), 1L, Long::sum));
        return new BatchView(id, header.businessDate(), header.currency(), header.createdAt(), counts, results);
    }

    private Map<String, List<LedgerRecord>> byReference(List<SourcedRecord> sources, String side) {
        return sources.stream().filter(s -> s.side().equals(side)).map(SourcedRecord::record)
            .collect(Collectors.groupingBy(LedgerRecord::reference));
    }

    public StoredResult getResult(String id) {
        return jdbc.query("SELECT * FROM recon_result WHERE id = ?", (rs, n) -> result(rs), id).stream().findFirst()
            .orElseThrow(() -> ApiException.notFound("Result not found."));
    }

    public ResultView getResultView(String id) {
        var stored = getResult(id);
        return getBatch(stored.batchId()).results().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    public boolean updateReview(StoredResult current, ReviewState target, String note) {
        return jdbc.update("""
            UPDATE recon_result SET review_state = ?, version = version + 1, resolution_note = ?
            WHERE id = ? AND version = ?
            """, target.name(), target == ReviewState.RESOLVED ? note : null, current.id(), current.version()) == 1;
    }

    public void insertEvent(StoredResult current, ReviewInput input, String createdAt) {
        jdbc.update("""
            INSERT INTO review_event(result_id, actor, from_state, to_state, resulting_version, note, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, current.id(), input.actor().strip(), current.state().name(), input.targetState().name(),
            current.version() + 1, input.note().strip(), createdAt);
    }

    public AuditPage events(String id, long afterSequence, int limit) {
        if (afterSequence < 0 || limit < 1 || limit > 200) {
            throw ApiException.badRequest("afterSequence must be nonnegative and limit must be between 1 and 200.");
        }
        getResult(id);
        // Fetch one extra row to detect another page without counting the full history.
        var rows = jdbc.query("""
            SELECT * FROM review_event
            WHERE result_id = ? AND sequence > ? ORDER BY sequence LIMIT ?
            """, (rs, n) ->
            new AuditEvent(rs.getLong("sequence"), id, rs.getString("actor"),
                ReviewState.valueOf(rs.getString("from_state")), ReviewState.valueOf(rs.getString("to_state")),
                rs.getInt("resulting_version"), rs.getString("note"), rs.getString("created_at")), id, afterSequence, limit + 1);
        boolean hasMore = rows.size() > limit;
        var events = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        return new AuditPage(events, hasMore ? events.get(events.size() - 1).sequence() : null);
    }

    private StoredResult result(ResultSet rs) throws SQLException {
        return new StoredResult(rs.getString("id"), rs.getString("batch_id"), rs.getString("reference"),
            Outcome.valueOf(rs.getString("outcome")), ReviewState.valueOf(rs.getString("review_state")),
            rs.getInt("version"), rs.getString("resolution_note"));
    }

    private ResultView view(StoredResult r, List<LedgerRecord> left, List<LedgerRecord> right) {
        return new ResultView(r.id(), r.reference(), r.outcome(), r.state(), r.version(), r.resolutionNote(), left, right);
    }
}
