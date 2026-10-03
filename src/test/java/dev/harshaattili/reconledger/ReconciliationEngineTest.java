package dev.harshaattili.reconledger;

import static dev.harshaattili.reconledger.Model.*;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReconciliationEngineTest {
    private final BatchNormalizer normalizer = new BatchNormalizer();
    private final ReconciliationEngine engine = new ReconciliationEngine();

    @Test void usesDecimalEqualityAndPreservesNegativeReversals() {
        var matches = reconcile(List.of(row("l1", "REF", "-0.10")), List.of(row("r1", "REF", "-0.1")));
        assertThat(matches.get(0).outcome()).isEqualTo(Outcome.MATCHED);
        assertThat(matches.get(0).leftRecords().get(0).amount()).isEqualByComparingTo("-0.10");
    }

    @Test void detectsAllFiveOutcomesInReferenceOrder() {
        var matches = reconcile(
            List.of(row("l5", "E", "1"), row("l1", "A", "10"), row("l2", "B", "10"),
                    row("l3", "D", "4"), row("l4", "E", "1")),
            List.of(row("r1", "A", "10"), row("r2", "B", "11"), row("r3", "C", "3"), row("r4", "E", "2")));
        assertThat(matches).extracting(Match::reference).containsExactly("A", "B", "C", "D", "E");
        assertThat(matches).extracting(Match::outcome).containsExactly(Outcome.MATCHED,
            Outcome.AMOUNT_MISMATCH, Outcome.MISSING_LEFT, Outcome.MISSING_RIGHT, Outcome.DUPLICATE_REFERENCE);
        assertThat(matches.get(4).leftRecords()).hasSize(2);
    }

    @Test void duplicatesStayAmbiguousEvenWithoutCounterpart() {
        assertThat(reconcile(List.of(row("l1", "A", "1"), row("l2", "A", "1")), List.of()).get(0).outcome())
            .isEqualTo(Outcome.DUPLICATE_REFERENCE);
    }

    @Test void digestIgnoresInputOrderAndEquivalentDecimalScale() {
        var a = input(List.of(row("2", "B", "20.00"), row("1", "A", "10.0")), List.of());
        var b = input(List.of(row("1", "A", "10.000"), row("2", "B", "20")), List.of());
        assertThat(normalizer.fingerprint(normalizer.normalize(a))).isEqualTo(normalizer.fingerprint(normalizer.normalize(b)));
        assertThat(normalizer.fingerprint(normalizer.normalize(a)))
            .isNotEqualTo(normalizer.fingerprint(normalizer.normalize(input(List.of(), b.leftRecords()))));
    }

    @Test void rejectsDuplicateRecordIdsButAllowsSameIdAcrossSides() {
        assertThatThrownBy(() -> normalizer.normalize(input(List.of(row("1", "A", "1"), row("1", "B", "1")), List.of())))
            .isInstanceOf(ApiException.class).hasMessageContaining("unique");
        assertThat(reconcile(List.of(row("1", "A", "1")), List.of(row("1", "A", "1"))).get(0).outcome()).isEqualTo(Outcome.MATCHED);
    }

    @Test void rejectsFractionalCentsWithoutRounding() {
        assertThatThrownBy(() -> normalizer.normalize(input(List.of(row("1", "A", "0.001")), List.of())))
            .isInstanceOf(ApiException.class).hasMessageContaining("two decimal");
    }

    @Test void rejectsOverflowAndEmptyBatch() {
        assertThatThrownBy(() -> normalizer.normalize(input(List.of(row("1", "A", "1000000000000")), List.of())))
            .isInstanceOf(ApiException.class).hasMessageContaining("range");
        assertThatThrownBy(() -> normalizer.normalize(input(List.of(), List.of())))
            .isInstanceOf(ApiException.class).hasMessageContaining("At least one");
    }

    @Test void rejectsExtremeExponentsBeforeAttemptingDecimalRescaling() {
        for (String amount : List.of("1E+100000000", "1E-100000000")) {
            assertThatThrownBy(() -> normalizer.normalize(input(List.of(row("1", "A", amount)), List.of())))
                .isInstanceOf(ApiException.class);
        }
        assertThat(normalizer.normalize(input(List.of(row("1", "A", "0E-100000000")), List.of()))
            .leftRecords().get(0).amount()).isEqualByComparingTo("0.00");
    }

    @Test void referencesAreCaseSensitiveAndInputsAreNotMutated() {
        var left = List.of(row("1", "ref", "1"));
        var matches = reconcile(left, List.of(row("1", "REF", "1")));
        assertThat(matches).hasSize(2);
        assertThat(left.get(0).amount().scale()).isZero();
    }

    private List<Match> reconcile(List<LedgerRecord> left, List<LedgerRecord> right) {
        return engine.reconcile(normalizer.normalize(input(left, right)));
    }

    static BatchInput input(List<LedgerRecord> left, List<LedgerRecord> right) {
        return new BatchInput(LocalDate.of(2026, 10, 3), "USD", left, right);
    }

    static LedgerRecord row(String id, String reference, String amount) {
        return new LedgerRecord(id, reference, new BigDecimal(amount));
    }
}
