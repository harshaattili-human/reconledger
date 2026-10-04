package dev.harshaattili.reconledger;

import static dev.harshaattili.reconledger.Model.*;
import static dev.harshaattili.reconledger.ReconciliationEngineTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ReconciliationApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean LedgerRepository repository;
    @Value("${recon.test.expected-database}") String expectedDatabase;

    @Test void usesTheRequestedDatabaseEngine() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            var metadata = connection.getMetaData();
            assertThat(metadata.getDatabaseProductName()).isEqualTo(expectedDatabase);
            System.out.println("Verified database engine: " + metadata.getDatabaseProductName()
                + " " + metadata.getDatabaseProductVersion());
        }
    }

    @Test void createsAndFetchesBatchWithSourceTraceability() throws Exception {
        var response = create(key(), sample()).andExpect(status().isCreated())
            .andExpect(header().string("Idempotency-Replayed", "false"))
            .andExpect(jsonPath("$.counts.AMOUNT_MISMATCH").value(1))
            .andExpect(jsonPath("$.results[0].reviewState").value("OPEN"))
            .andExpect(jsonPath("$.results[0].leftRecords[0].recordId").value("l1"))
            .andReturn();
        mvc.perform(get(response.getResponse().getHeader("Location"))).andExpect(status().isOk())
            .andExpect(content().json(response.getResponse().getContentAsString()));
    }

    @Test void identicalRetryReturnsSameBatchAndDoesNotDuplicateRows() throws Exception {
        String key = key();
        var original = body(create(key, sample()).andReturn());
        var retry = create(key, sample()).andExpect(status().isOk())
            .andExpect(header().string("Idempotency-Replayed", "true")).andReturn();
        assertThat(body(retry).get("id")).isEqualTo(original.get("id"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recon_batch WHERE idempotency_key = ?", Integer.class, key)).isEqualTo(1);
    }

    @Test void differentPayloadCannotReuseKey() throws Exception {
        String key = key();
        create(key, sample()).andExpect(status().isCreated());
        create(key, input(List.of(row("l1", "REF", "999")), List.of()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("different batch")));
    }

    @Test void concurrentRetriesProduceExactlyOneCommittedBatch() throws Exception {
        String key = key();
        forceInitialLookupCollision(key, 6);
        var responses = concurrently(6, () -> create(key, sample()).andReturn());
        assertThat(responses.stream().map(r -> r.getResponse().getStatus()).toList())
            .containsOnly(200, 201).filteredOn(s -> s == 201).hasSize(1);
        Set<String> ids = new HashSet<>();
        for (var response : responses) ids.add(body(response).get("id").asText());
        assertThat(ids).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recon_result WHERE batch_id = ?", Integer.class, ids.iterator().next())).isEqualTo(1);
    }

    @Test void conflictingConcurrentPayloadsCannotShareOneKey() throws Exception {
        String key = key();
        forceInitialLookupCollision(key, 2);
        AtomicInteger request = new AtomicInteger();
        var responses = concurrently(2, () -> create(key, request.getAndIncrement() == 0 ? sample()
            : input(List.of(row("other", "OTHER", "12.34")), List.of())).andReturn());
        assertThat(responses.stream().map(r -> r.getResponse().getStatus()).toList()).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recon_batch WHERE idempotency_key = ?", Integer.class, key)).isEqualTo(1);
        var winner = responses.stream().filter(r -> r.getResponse().getStatus() == 201).findFirst().orElseThrow();
        mvc.perform(get("/api/batches/" + body(winner).get("id").asText())).andExpect(status().isOk())
            .andExpect(content().json(winner.getResponse().getContentAsString()));
    }

    @Test void failedCreatorReleasesTheKeyForTheConcurrentRetry() throws Exception {
        String key = key();
        forceInitialLookupCollision(key, 2);
        AtomicBoolean failFirst = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (failFirst.getAndSet(false)) throw new DataAccessResourceFailureException("injected first creator failure");
            return invocation.callRealMethod();
        }).when(repository).insertResults(anyString(), anyList());
        var responses = concurrently(2, () -> create(key, sample()).andReturn());
        assertThat(responses.stream().map(r -> r.getResponse().getStatus()).toList()).containsExactlyInAnyOrder(201, 503);
        var replay = body(create(key, sample()).andExpect(status().isOk()).andReturn());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source_record WHERE batch_id = ?", Integer.class, replay.get("id").asText())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recon_batch WHERE idempotency_key = ?", Integer.class, key)).isEqualTo(1);
    }

    @Test void preservesExactDecimalBoundariesThroughTheDatabase() throws Exception {
        var rows = List.of(row("max", "MAX", "999999999999.99"), row("min", "MIN", "-999999999999.99"),
            row("penny", "PENNY", "0.01"));
        var response = body(create(key(), input(rows, rows)).andExpect(status().isCreated()).andReturn());
        var stored = repository.getBatch(response.get("id").asText());
        assertThat(stored.counts().get(Outcome.MATCHED)).isEqualTo(3L);
        assertThat(stored.results().get(0).leftRecords().get(0).amount()).isEqualByComparingTo("999999999999.99");
        assertThat(stored.results().get(1).leftRecords().get(0).amount()).isEqualByComparingTo("-999999999999.99");
        assertThat(stored.results().get(2).leftRecords().get(0).amount()).isEqualByComparingTo("0.01");
    }

    @Test void acceptsTheFullThousandRecordBoundWithoutLosingEvidence() throws Exception {
        var rows = IntStream.range(0, 500).mapToObj(i -> row("id" + i, "REF" + i, "1.01")).toList();
        var batch = body(create(key(), input(rows, rows)).andExpect(status().isCreated())
            .andExpect(jsonPath("$.results.length()").value(500))
            .andExpect(jsonPath("$.counts.MATCHED").value(500)).andReturn());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source_record WHERE batch_id = ?", Integer.class, batch.get("id").asText()))
            .isEqualTo(1000);
    }

    @Test void returnsReferencesAndEvidenceInCanonicalCaseSensitiveOrder() throws Exception {
        var rows = List.of(row("z", "a-ref", "1"), row("a", "A_REF", "1"), row("A", "A-REF", "1"),
            row("_", ".REF", "1"), row("B", "A_REF", "1"));
        var response = body(create(key(), input(rows, List.of())).andExpect(status().isCreated()).andReturn());
        var stored = repository.getBatch(response.get("id").asText());
        assertThat(stored.results()).extracting(ResultView::reference).containsExactly(".REF", "A-REF", "A_REF", "a-ref");
        assertThat(stored.results().get(2).leftRecords()).extracting(LedgerRecord::recordId).containsExactly("B", "a");
    }

    @Test void reviewLifecycleRecordsEveryTransitionAndReopeningClearsResolution() throws Exception {
        String id = exceptionId();
        review(id, ReviewState.IN_REVIEW, 0, "Investigating source file").andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(1));
        review(id, ReviewState.RESOLVED, 1, "Synthetic source correction verified").andExpect(status().isOk())
            .andExpect(jsonPath("$.resolutionNote").value("Synthetic source correction verified"));
        review(id, ReviewState.IN_REVIEW, 2, "New evidence requires another check").andExpect(status().isOk())
            .andExpect(jsonPath("$.resolutionNote").doesNotExist());
        mvc.perform(get("/api/results/{id}/events", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.events.length()").value(3))
            .andExpect(jsonPath("$.events[0].fromState").value("OPEN"))
            .andExpect(jsonPath("$.events[2].resultingVersion").value(3));
        mvc.perform(get("/api/results/{id}", id)).andExpect(jsonPath("$.leftRecords[0].amount").value(10.0));
    }

    @Test void staleReviewAndInvalidTransitionLeaveStateAndHistoryUnchanged() throws Exception {
        String id = exceptionId();
        review(id, ReviewState.RESOLVED, 0, "Skipping review").andExpect(status().isConflict());
        review(id, ReviewState.IN_REVIEW, 0, "Valid review").andExpect(status().isOk());
        review(id, ReviewState.RESOLVED, 0, "Stale browser tab").andExpect(status().isConflict());
        mvc.perform(get("/api/results/{id}/events", id)).andExpect(jsonPath("$.events.length()").value(1));
        mvc.perform(get("/api/results/{id}", id)).andExpect(jsonPath("$.reviewState").value("IN_REVIEW"));
    }

    @Test void simultaneousReviewersCannotOverwriteEachOther() throws Exception {
        String id = exceptionId();
        var responses = concurrently(2, () -> review(id, ReviewState.IN_REVIEW, 0, "Concurrent review").andReturn());
        assertThat(responses.stream().map(r -> r.getResponse().getStatus()).toList()).containsExactlyInAnyOrder(200, 409);
        mvc.perform(get("/api/results/{id}/events", id)).andExpect(jsonPath("$.events.length()").value(1));
    }

    @Test void matchedResultsCannotBeManuallyChanged() throws Exception {
        var batch = body(create(key(), input(List.of(row("l", "REF", "1")), List.of(row("r", "REF", "1")))).andReturn());
        String id = batch.at("/results/0/id").asText();
        review(id, ReviewState.IN_REVIEW, 0, "Unnecessary review").andExpect(status().isConflict());
        mvc.perform(get("/api/results/{id}/events", id)).andExpect(jsonPath("$.events.length()").value(0));
    }

    @Test void batchFailureRollsBackHeaderAndSourceRecordsAndAllowsRetry() throws Exception {
        String key = key();
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM source_record", Integer.class);
        doThrow(new DataAccessResourceFailureException("injected result write failure"))
            .when(repository).insertResults(anyString(), anyList());
        create(key, sample()).andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recon_batch WHERE idempotency_key = ?", Integer.class, key)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM source_record", Integer.class)).isEqualTo(before);
        doCallRealMethod().when(repository).insertResults(anyString(), anyList());
        create(key, sample()).andExpect(status().isCreated());
    }

    @Test void auditFailureRollsBackReviewStateAndVersion() throws Exception {
        String id = exceptionId();
        doThrow(new DataAccessResourceFailureException("injected audit write failure"))
            .when(repository).insertEvent(any(), any(), anyString());
        review(id, ReviewState.IN_REVIEW, 0, "Test atomic update").andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/results/{id}", id)).andExpect(jsonPath("$.reviewState").value("OPEN"))
            .andExpect(jsonPath("$.version").value(0));
        mvc.perform(get("/api/results/{id}/events", id)).andExpect(jsonPath("$.events.length()").value(0));
    }

    @Test void rejectsMissingKeyFractionalCentsAndDuplicateIds() throws Exception {
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(sample())))
            .andExpect(status().isBadRequest());
        create(key(), input(List.of(row("l", "REF", "0.001")), List.of())).andExpect(status().isBadRequest());
        create(key(), input(List.of(row("l", "REF", "1"), row("l", "OTHER", "1")), List.of()))
            .andExpect(status().isBadRequest());
    }

    @Test void rejectsMissingFieldsUnknownFieldsAndOversizedBatches() throws Exception {
        for (String payload : List.of("{}", "{\"unexpected\":true}", "{\"businessDate\":\"invalid\"}")) {
            mvc.perform(post("/api/batches").header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest());
        }
        var records = IntStream.range(0, 501).mapToObj(i -> row("id" + i, "REF" + i, "1")).toList();
        create(key(), input(records, List.of())).andExpect(status().isBadRequest());
    }

    @Test void rejectsBlankReviewNoteAndMissingVersion() throws Exception {
        String id = exceptionId();
        review(id, ReviewState.IN_REVIEW, 0, "   ").andExpect(status().isBadRequest());
        mvc.perform(post("/api/results/{id}/reviews", id).contentType(MediaType.APPLICATION_JSON)
            .content("{\"targetState\":\"IN_REVIEW\",\"actor\":\"demo-reviewer\",\"note\":\"Missing version\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test void returnsNotFoundAndExposesOnlyBasicHealth() throws Exception {
        mvc.perform(get("/api/batches/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/results/{id}/events", UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
    }

    @Test void boundsAuditPagesAndStopsAtAnExactlyFullLastPage() throws Exception {
        String id = exceptionId();
        for (int version = 0; version < 205; version++) {
            review(id, version % 2 == 0 ? ReviewState.IN_REVIEW : ReviewState.OPEN, version, "Synthetic review " + version)
                .andExpect(status().isOk());
        }
        var first = body(mvc.perform(get("/api/results/{id}/events", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.events.length()").value(50)).andReturn());
        assertThat(first.get("nextAfterSequence")).isEqualTo(first.at("/events/49/sequence"));
        var large = body(mvc.perform(get("/api/results/{id}/events", id).param("limit", "200"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.events.length()").value(200)).andReturn());
        assertThat(large.get("nextAfterSequence")).isEqualTo(large.at("/events/199/sequence"));
        var last = body(mvc.perform(get("/api/results/{id}/events", id).param("limit", "5")
            .param("afterSequence", large.get("nextAfterSequence").asText())).andExpect(status().isOk())
            .andExpect(jsonPath("$.events.length()").value(5))
            .andExpect(jsonPath("$.events[0].resultingVersion").value(201))
            .andExpect(jsonPath("$.events[4].resultingVersion").value(205)).andReturn());
        assertThat(last.get("nextAfterSequence").isNull()).isTrue();
        var empty = body(mvc.perform(get("/api/results/{id}/events", id)
            .param("afterSequence", Long.toString(Long.MAX_VALUE))).andExpect(status().isOk())
            .andExpect(jsonPath("$.events.length()").value(0)).andReturn());
        assertThat(empty.get("nextAfterSequence").isNull()).isTrue();
    }

    @Test void resumesAcrossSequenceGapsAndIncludesLaterReviewsWithoutRepeatingEvents() throws Exception {
        String id = exceptionId();
        String other = exceptionId();
        review(id, ReviewState.IN_REVIEW, 0, "First").andExpect(status().isOk());
        review(other, ReviewState.IN_REVIEW, 0, "Other result").andExpect(status().isOk());
        review(id, ReviewState.OPEN, 1, "Second").andExpect(status().isOk());
        review(id, ReviewState.IN_REVIEW, 2, "Third").andExpect(status().isOk());
        // Equal timestamps must not change ordering or cause a cursor to skip an event.
        jdbc.update("UPDATE review_event SET created_at = ? WHERE result_id = ?", "2026-10-04T12:00:00Z", id);
        var first = body(mvc.perform(get("/api/results/{id}/events", id).param("limit", "2"))
            .andExpect(status().isOk()).andReturn());
        long cursor = first.get("nextAfterSequence").asLong();
        assertThat(cursor).isGreaterThan(first.at("/events/0/sequence").asLong() + 1);
        review(id, ReviewState.OPEN, 3, "Added between pages").andExpect(status().isOk());
        var second = body(mvc.perform(get("/api/results/{id}/events", id).param("limit", "2")
            .param("afterSequence", Long.toString(cursor))).andExpect(status().isOk())
            .andExpect(jsonPath("$.events.length()").value(2))
            .andExpect(jsonPath("$.events[0].resultingVersion").value(3))
            .andExpect(jsonPath("$.events[1].resultingVersion").value(4)).andReturn());
        assertThat(second.get("nextAfterSequence").isNull()).isTrue();
        for (var page : List.of(first, second)) {
            for (var event : page.get("events")) assertThat(event.get("resultId").asText()).isEqualTo(id);
        }
        var replay = body(mvc.perform(get("/api/results/{id}/events", id).param("limit", "2")
            .param("afterSequence", Long.toString(cursor))).andExpect(status().isOk()).andReturn());
        assertThat(replay).isEqualTo(second);
    }

    @Test void rejectsInvalidAuditPageParametersWithProblemDetails() throws Exception {
        String id = exceptionId();
        for (String limit : List.of("0", "-1", "201", "2147483648", "abc", "1.5")) {
            mvc.perform(get("/api/results/{id}/events", id).param("limit", limit))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
        for (String after : List.of("-1", "9223372036854775808", "abc", "1.5")) {
            mvc.perform(get("/api/results/{id}/events", id).param("afterSequence", after))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
    }

    @Test void pageReadDuringUncommittedReviewCanResumeAfterCommit() throws Exception {
        String id = exceptionId();
        review(id, ReviewState.IN_REVIEW, 0, "Committed first review").andExpect(status().isOk());
        long cursor = repository.events(id, 0, 1).events().get(0).sequence();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object value = invocation.callRealMethod();
            inserted.countDown();
            if (!commit.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting to commit");
            return value;
        }).when(repository).insertEvent(any(), any(), anyString());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            var pending = worker.submit(() -> review(id, ReviewState.OPEN, 1, "Pending review").andReturn());
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            var before = body(mvc.perform(get("/api/results/{id}/events", id)
                .param("afterSequence", Long.toString(cursor))).andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(0)).andReturn());
            assertThat(before.get("nextAfterSequence").isNull()).isTrue();
            commit.countDown();
            assertThat(pending.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            mvc.perform(get("/api/results/{id}/events", id).param("afterSequence", Long.toString(cursor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].resultingVersion").value(2));
        } finally {
            commit.countDown();
            worker.shutdownNow();
            worker.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test void rolledBackAuditInsertDoesNotAppearInLaterPages() throws Exception {
        String id = exceptionId();
        review(id, ReviewState.IN_REVIEW, 0, "First").andExpect(status().isOk());
        long cursor = repository.events(id, 0, 1).events().get(0).sequence();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataAccessResourceFailureException("injected failure after audit insert");
        }).when(repository).insertEvent(any(), any(), anyString());
        review(id, ReviewState.OPEN, 1, "Must roll back").andExpect(status().isServiceUnavailable());
        doCallRealMethod().when(repository).insertEvent(any(), any(), anyString());
        review(id, ReviewState.OPEN, 1, "Successful retry").andExpect(status().isOk());
        var page = body(mvc.perform(get("/api/results/{id}/events", id).param("limit", "1")
            .param("afterSequence", Long.toString(cursor))).andExpect(status().isOk())
            .andExpect(jsonPath("$.events.length()").value(1))
            .andExpect(jsonPath("$.events[0].note").value("Successful retry"))
            .andExpect(jsonPath("$.events[0].resultingVersion").value(2)).andReturn());
        assertThat(page.at("/events/0/sequence").asLong()).isGreaterThan(cursor + 1);
        assertThat(page.get("nextAfterSequence").isNull()).isTrue();
    }

    private org.springframework.test.web.servlet.ResultActions create(String key, BatchInput input) throws Exception {
        return mvc.perform(post("/api/batches").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(input)));
    }

    private org.springframework.test.web.servlet.ResultActions review(String id, ReviewState state, int version, String note) throws Exception {
        return mvc.perform(post("/api/results/{id}/reviews", id).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(new ReviewInput(state, version, "demo-reviewer", note))));
    }

    private String exceptionId() throws Exception { return body(create(key(), sample()).andExpect(status().isCreated()).andReturn()).at("/results/0/id").asText(); }
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    private static String key() { return "test-" + UUID.randomUUID(); }
    private static BatchInput sample() { return input(List.of(row("l1", "REF", "10")), List.of(row("r1", "REF", "11"))); }

    private void forceInitialLookupCollision(String key, int contenders) {
        CyclicBarrier initialReads = new CyclicBarrier(contenders);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            Object found = invocation.callRealMethod();
            if (calls.getAndIncrement() < contenders) {
                assertThat((Optional<?>) found).isEmpty();
                initialReads.await(10, TimeUnit.SECONDS);
            }
            return found;
        }).when(repository).findByKey(key);
    }

    private static <T> List<T> concurrently(int workers, Callable<T> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) futures.add(pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new TimeoutException("Start barrier timed out");
                return action.call();
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }
}
