package dev.harshaattili.reconledger;

import static dev.harshaattili.reconledger.Model.*;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ReconciliationController {
    private final ReconciliationService service;
    private final LedgerRepository repository;

    public ReconciliationController(ReconciliationService service, LedgerRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @PostMapping("/batches")
    public ResponseEntity<BatchView> create(
        @RequestHeader(value = "Idempotency-Key", required = false) String key,
        @Valid @RequestBody BatchInput input) {
        var created = service.create(key, input);
        return ResponseEntity.status(created.replayed() ? 200 : 201)
            .location(URI.create("/api/batches/" + created.batch().id()))
            .header("Idempotency-Replayed", Boolean.toString(created.replayed()))
            .body(created.batch());
    }

    @GetMapping("/batches/{id}")
    public BatchView batch(@PathVariable String id) { return repository.getBatch(id); }

    @GetMapping("/results/{id}")
    public ResultView result(@PathVariable String id) { return repository.getResultView(id); }

    @PostMapping("/results/{id}/reviews")
    public ResultView review(@PathVariable String id, @Valid @RequestBody ReviewInput input) {
        return service.review(id, input);
    }

    @GetMapping("/results/{id}/events")
    public AuditPage events(@PathVariable String id,
                            @RequestParam(defaultValue = "0") long afterSequence,
                            @RequestParam(defaultValue = "50") int limit) {
        return repository.events(id, afterSequence, limit);
    }
}
