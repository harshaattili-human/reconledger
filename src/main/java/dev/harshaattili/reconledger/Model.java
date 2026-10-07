package dev.harshaattili.reconledger;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class Model {
    private Model() {}

    public record LedgerRecord(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._:/-]{1,80}") String recordId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._:/-]{1,80}") String reference,
        @NotNull BigDecimal amount) {}

    public record BatchInput(
        @NotNull LocalDate businessDate,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull @Size(max = 500) List<@NotNull @Valid LedgerRecord> leftRecords,
        @NotNull @Size(max = 500) List<@NotNull @Valid LedgerRecord> rightRecords) {}

    public enum Outcome { MATCHED, AMOUNT_MISMATCH, MISSING_LEFT, MISSING_RIGHT, DUPLICATE_REFERENCE }
    public enum ReviewState { NOT_REQUIRED, OPEN, IN_REVIEW, RESOLVED }

    public record Match(String reference, Outcome outcome,
                        List<LedgerRecord> leftRecords, List<LedgerRecord> rightRecords) {}

    public record ResultView(String id, String reference, Outcome outcome, ReviewState reviewState,
                             int version, String resolutionNote,
                             List<LedgerRecord> leftRecords, List<LedgerRecord> rightRecords) {}

    public record BatchView(String id, LocalDate businessDate, String currency, String createdAt,
                            Map<Outcome, Long> counts, List<ResultView> results) {}

    public record CreateResult(BatchView batch, boolean replayed) {}

    public record BatchSummary(String id, long sequence, LocalDate businessDate,
                               String currency, String createdAt) {}

    public record BatchPage(List<BatchSummary> batches, Long nextBeforeSequence) {}

    public record ReviewInput(@NotNull ReviewState targetState, @NotNull @Min(0) Integer expectedVersion,
                               @NotBlank @Size(max = 80) String actor,
                               @NotBlank @Size(max = 500) String note) {}

    public record AuditEvent(long sequence, String resultId, String actor,
                              ReviewState fromState, ReviewState toState,
                              int resultingVersion, String note, String createdAt) {}

    public record AuditPage(List<AuditEvent> events, Long nextAfterSequence) {}
}
