package dev.harshaattili.reconledger;

import static dev.harshaattili.reconledger.Model.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class BatchNormalizer {
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");

    public BatchInput normalize(BatchInput input) {
        if (input.leftRecords().isEmpty() && input.rightRecords().isEmpty()) {
            throw ApiException.badRequest("At least one transaction record is required.");
        }
        return new BatchInput(input.businessDate(), input.currency(),
            normalizeSide(input.leftRecords(), "left"), normalizeSide(input.rightRecords(), "right"));
    }

    private List<LedgerRecord> normalizeSide(List<LedgerRecord> records, String side) {
        Set<String> ids = new HashSet<>();
        List<LedgerRecord> normalized = new ArrayList<>();
        for (LedgerRecord record : records) {
            if (!ids.add(record.recordId())) {
                throw ApiException.badRequest("Record IDs must be unique within the " + side + " side.");
            }
            BigDecimal amount;
            try {
                amount = record.amount().setScale(2, RoundingMode.UNNECESSARY);
            } catch (ArithmeticException ex) {
                throw ApiException.badRequest("Amounts must be exactly representable with two decimal places.");
            }
            if (amount.abs().compareTo(MAX_AMOUNT) > 0) {
                throw ApiException.badRequest("Amount exceeds the supported range.");
            }
            normalized.add(new LedgerRecord(record.recordId(), record.reference(), amount));
        }
        normalized.sort(Comparator.comparing(LedgerRecord::recordId));
        return List.copyOf(normalized);
    }

    public String fingerprint(BatchInput normalized) {
        // Length-prefixing avoids ambiguous concatenations, even if identifier rules evolve.
        StringBuilder canonical = new StringBuilder();
        field(canonical, "v1");
        field(canonical, normalized.businessDate().toString());
        field(canonical, normalized.currency());
        appendSide(canonical, "left", normalized.leftRecords());
        appendSide(canonical, "right", normalized.rightRecords());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Required SHA-256 algorithm is unavailable", ex);
        }
    }

    private void appendSide(StringBuilder target, String side, List<LedgerRecord> records) {
        field(target, side);
        field(target, Integer.toString(records.size()));
        for (LedgerRecord record : records) {
            field(target, record.recordId());
            field(target, record.reference());
            field(target, record.amount().toPlainString());
        }
    }

    private void field(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }
}
