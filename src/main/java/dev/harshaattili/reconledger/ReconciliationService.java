package dev.harshaattili.reconledger;

import static dev.harshaattili.reconledger.Model.*;

import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReconciliationService {
    private final BatchNormalizer normalizer;
    private final ReconciliationEngine engine;
    private final LedgerRepository repository;
    private final TransactionTemplate transaction;

    public ReconciliationService(BatchNormalizer normalizer, ReconciliationEngine engine,
                                  LedgerRepository repository, PlatformTransactionManager manager) {
        this.normalizer = normalizer;
        this.engine = engine;
        this.repository = repository;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setTimeout(15);
    }

    public CreateResult create(String key, BatchInput raw) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,80}")) {
            throw ApiException.badRequest("Idempotency-Key must contain 8-80 letters, digits, dots, underscores, colons or hyphens.");
        }
        BatchInput input = normalizer.normalize(raw);
        String fingerprint = normalizer.fingerprint(input);
        var existing = repository.findByKey(key);
        if (existing.isPresent()) return replay(existing.get(), fingerprint);
        try {
            return transaction.execute(status -> {
                String id = UUID.randomUUID().toString();
                repository.insertBatch(id, key, fingerprint, input, Instant.now().toString());
                repository.insertResults(id, engine.reconcile(input));
                return new CreateResult(repository.getBatch(id), false);
            });
        } catch (DuplicateKeyException conflict) {
            // The losing INSERT transaction has rolled back before this read.
            // PostgreSQL cannot run a recovery SELECT inside an aborted transaction.
            var winner = repository.findByKey(key);
            if (winner.isEmpty()) throw conflict;
            return replay(winner.get(), fingerprint);
        }
    }

    private CreateResult replay(LedgerRepository.ExistingBatch existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("This Idempotency-Key is already associated with a different batch.");
        }
        return new CreateResult(repository.getBatch(existing.id()), true);
    }

    public ResultView review(String id, ReviewInput input) {
        return transaction.execute(status -> {
            var current = repository.getResult(id);
            if (current.version() != input.expectedVersion()) {
                throw ApiException.conflict("Stale version. Fetch the result and review the latest state before retrying.");
            }
            boolean allowed = switch (current.state()) {
                case OPEN -> input.targetState() == ReviewState.IN_REVIEW;
                case IN_REVIEW -> input.targetState() == ReviewState.RESOLVED || input.targetState() == ReviewState.OPEN;
                case RESOLVED -> input.targetState() == ReviewState.IN_REVIEW;
                case NOT_REQUIRED -> false;
            };
            if (!allowed) throw ApiException.conflict("Review transition is not allowed from the current state.");
            if (!repository.updateReview(current, input.targetState(), input.note().strip())) {
                throw ApiException.conflict("Another review changed this result. Fetch the latest version before retrying.");
            }
            repository.insertEvent(current, input, Instant.now().toString());
            return repository.getResultView(id);
        });
    }
}
