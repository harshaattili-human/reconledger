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
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:recon-api-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@AutoConfigureMockMvc
class ReconciliationApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean LedgerRepository repository;

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
        var responses = concurrently(6, () -> create(key, sample()).andReturn());
        assertThat(responses.stream().map(r -> r.getResponse().getStatus()).toList())
            .containsOnly(200, 201).filteredOn(s -> s == 201).hasSize(1);
        Set<String> ids = new HashSet<>();
        for (var response : responses) ids.add(body(response).get("id").asText());
        assertThat(ids).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recon_result WHERE batch_id = ?", Integer.class, ids.iterator().next())).isEqualTo(1);
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
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].fromState").value("OPEN"))
            .andExpect(jsonPath("$[2].resultingVersion").value(3));
        mvc.perform(get("/api/results/{id}", id)).andExpect(jsonPath("$.leftRecords[0].amount").value(10.0));
    }

    @Test void staleReviewAndInvalidTransitionLeaveStateAndHistoryUnchanged() throws Exception {
        String id = exceptionId();
        review(id, ReviewState.RESOLVED, 0, "Skipping review").andExpect(status().isConflict());
        review(id, ReviewState.IN_REVIEW, 0, "Valid review").andExpect(status().isOk());
        review(id, ReviewState.RESOLVED, 0, "Stale browser tab").andExpect(status().isConflict());
        mvc.perform(get("/api/results/{id}/events", id)).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/results/{id}", id)).andExpect(jsonPath("$.reviewState").value("IN_REVIEW"));
    }

    @Test void simultaneousReviewersCannotOverwriteEachOther() throws Exception {
        String id = exceptionId();
        var responses = concurrently(2, () -> review(id, ReviewState.IN_REVIEW, 0, "Concurrent review").andReturn());
        assertThat(responses.stream().map(r -> r.getResponse().getStatus()).toList()).containsExactlyInAnyOrder(200, 409);
        mvc.perform(get("/api/results/{id}/events", id)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test void matchedResultsCannotBeManuallyChanged() throws Exception {
        var batch = body(create(key(), input(List.of(row("l", "REF", "1")), List.of(row("r", "REF", "1")))).andReturn());
        String id = batch.at("/results/0/id").asText();
        review(id, ReviewState.IN_REVIEW, 0, "Unnecessary review").andExpect(status().isConflict());
        mvc.perform(get("/api/results/{id}/events", id)).andExpect(jsonPath("$.length()").value(0));
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
        mvc.perform(get("/api/results/{id}/events", id)).andExpect(jsonPath("$.length()").value(0));
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
