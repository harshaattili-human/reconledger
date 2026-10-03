package dev.harshaattili.reconledger;

import static dev.harshaattili.reconledger.Model.*;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationEngine {
    public List<Match> reconcile(BatchInput normalized) {
        Map<String, List<LedgerRecord>> left = group(normalized.leftRecords());
        Map<String, List<LedgerRecord>> right = group(normalized.rightRecords());
        SortedSet<String> references = new TreeSet<>(left.keySet());
        references.addAll(right.keySet());
        List<Match> matches = new ArrayList<>();
        for (String reference : references) {
            List<LedgerRecord> l = left.getOrDefault(reference, List.of());
            List<LedgerRecord> r = right.getOrDefault(reference, List.of());
            Outcome outcome;
            // Never guess a many-to-many pairing, even when aggregate amounts agree.
            if (l.size() > 1 || r.size() > 1) outcome = Outcome.DUPLICATE_REFERENCE;
            else if (l.isEmpty()) outcome = Outcome.MISSING_LEFT;
            else if (r.isEmpty()) outcome = Outcome.MISSING_RIGHT;
            else if (l.get(0).amount().compareTo(r.get(0).amount()) == 0) outcome = Outcome.MATCHED;
            else outcome = Outcome.AMOUNT_MISMATCH;
            matches.add(new Match(reference, outcome, List.copyOf(l), List.copyOf(r)));
        }
        return List.copyOf(matches);
    }

    private Map<String, List<LedgerRecord>> group(List<LedgerRecord> records) {
        return records.stream().collect(Collectors.groupingBy(LedgerRecord::reference));
    }
}
