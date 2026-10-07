package dev.harshaattili.reconledger;

import static dev.harshaattili.reconledger.Model.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class BatchListApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReconciliationService service;
    @Autowired PlatformTransactionManager transactions;

    @Test void boundsPagesAndTraversesWithoutRepeatingTheLookahead() throws Exception {
        LocalDate date = LocalDate.of(2080, 1, 1);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 105; i++) ids.add(create(date, "USD").id());
        Collections.reverse(ids);
        // Equal timestamps cannot break ties or replace the numeric ordering contract.
        jdbc.update("UPDATE recon_batch SET created_at = ? WHERE business_date = ?",
            "2026-10-06T12:00:00Z", java.sql.Date.valueOf(date));
        var first = list(date, null, null, null);
        assertThat(ids(first)).isEqualTo(ids.subList(0, 50));
        assertThat(first.get("nextBeforeSequence")).isEqualTo(first.at("/batches/49/sequence"));
        var large = list(date, null, null, 100);
        assertThat(ids(large)).isEqualTo(ids.subList(0, 100));
        long cursor = large.get("nextBeforeSequence").asLong();
        assertThat(cursor).isEqualTo(large.at("/batches/99/sequence").asLong());
        var last = list(date, null, cursor, 5);
        assertThat(ids(last)).isEqualTo(ids.subList(100, 105));
        assertThat(last.get("nextBeforeSequence").isNull()).isTrue();
        assertThat(list(date, null, cursor, 5)).isEqualTo(last);
        assertThat(ids(list(date, null, last.at("/batches/4/sequence").asLong(), 5))).isEmpty();
        for (var summary : large.get("batches")) {
            Set<String> fields = new HashSet<>();
            summary.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("id", "sequence", "businessDate", "currency", "createdAt");
        }
        mvc.perform(get("/api/batches/{id}", ids.get(0))).andExpect(status().isOk())
            .andExpect(jsonPath("$.results.length()").value(1));
    }

    @Test void appliesExactFiltersBeforeThePageLimit() throws Exception {
        LocalDate date = LocalDate.of(2080, 1, 2);
        var usd = create(date, "USD");
        var eur = create(date, "EUR");
        var otherDate = create(date.plusDays(1), "EUR");
        assertThat(ids(list(date, null, null, 100))).containsExactly(eur.id(), usd.id());
        assertThat(ids(list(date, "EUR", null, 1))).containsExactly(eur.id());
        assertThat(list(date, "EUR", null, 1).get("nextBeforeSequence").isNull()).isTrue();
        assertThat(ids(list(null, "EUR", null, 100))).contains(otherDate.id(), eur.id()).doesNotContain(usd.id());
        assertThat(ids(list(date, "JPY", null, 1))).isEmpty();
        assertThat(ids(list(LocalDate.of(2080, 12, 31), null, null, 1))).isEmpty();
    }

    @Test void newBatchesAndReplaysDoNotShiftOlderPageBoundaries() throws Exception {
        LocalDate date = LocalDate.of(2080, 1, 4);
        String key = "list-" + UUID.randomUUID();
        var oldest = service.create(key, input(date, "USD")).batch();
        var newer = create(date, "USD");
        var first = list(date, null, null, 1);
        long cursor = first.get("nextBeforeSequence").asLong();
        var newest = create(date, "USD");
        assertThat(service.create(key, input(date, "USD")).replayed()).isTrue();
        assertThat(ids(list(date, null, cursor, 1))).containsExactly(oldest.id());
        assertThat(ids(list(date, null, null, 100))).containsExactly(newest.id(), newer.id(), oldest.id());
    }

    @Test void skipsRolledBackAllocationsWithoutAssumingConsecutiveSequences() throws Exception {
        LocalDate date = LocalDate.of(2080, 1, 5);
        var oldest = create(date, "USD");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            create(date, "USD");
            status.setRollbackOnly();
        });
        var newest = create(date, "USD");
        var all = list(date, null, null, 100);
        assertThat(ids(all)).containsExactly(newest.id(), oldest.id());
        assertThat(all.at("/batches/0/sequence").asLong())
            .isGreaterThan(all.at("/batches/1/sequence").asLong() + 1);
        var first = list(date, null, null, 1);
        assertThat(ids(list(date, null, first.get("nextBeforeSequence").asLong(), 1)))
            .containsExactly(oldest.id());
    }

    @Test void aLateCommitRequiresRefreshingFromTheFirstPage() throws Exception {
        LocalDate date = LocalDate.of(2080, 1, 6);
        var oldest = create(date, "USD");
        var inserted = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        var pending = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
            var batch = create(date, "USD");
            inserted.countDown();
            try {
                if (!commit.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Commit barrier timed out");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
            return batch;
        }));
        try {
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            var newest = create(date, "USD");
            var first = list(date, null, null, 1);
            assertThat(ids(first)).containsExactly(newest.id());
            var last = list(date, null, first.get("nextBeforeSequence").asLong(), 1);
            assertThat(ids(last)).containsExactly(oldest.id());
            assertThat(last.get("nextBeforeSequence").isNull()).isTrue();
            commit.countDown();
            var late = pending.get(10, TimeUnit.SECONDS);
            assertThat(ids(list(date, null, last.at("/batches/0/sequence").asLong(), 1))).isEmpty();
            assertThat(ids(list(date, null, null, 100))).containsExactly(newest.id(), late.id(), oldest.id());
        } finally {
            commit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void rejectsInvalidBoundsAndFilterValuesWithProblemDetails() throws Exception {
        Map<String, List<String>> invalid = Map.of(
            "limit", List.of("0", "-1", "101", "2147483648", "abc", "1.5"),
            "beforeSequence", List.of("0", "-1", "9223372036854775808", "abc", "1.5"),
            "currency", List.of("", "usd", "US", "USDD", " USD", "USD' OR 1=1"),
            "businessDate", List.of("yesterday", "2026-02-30", "06-10-2026"));
        for (var entry : invalid.entrySet()) {
            for (String value : entry.getValue()) {
                mvc.perform(get("/api/batches").param(entry.getKey(), value))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
            }
        }
        assertThat(ids(list(null, null, 1L, 1))).isEmpty();
        mvc.perform(get("/api/batches").param("beforeSequence", Long.toString(Long.MAX_VALUE)))
            .andExpect(status().isOk());
    }

    private BatchView create(LocalDate date, String currency) {
        return service.create("list-" + UUID.randomUUID(), input(date, currency)).batch();
    }

    private BatchInput input(LocalDate date, String currency) {
        return new BatchInput(date, currency,
            List.of(new LedgerRecord("left", "REF", BigDecimal.ONE)), List.of());
    }

    private JsonNode list(LocalDate date, String currency, Long cursor, Integer limit) throws Exception {
        var request = get("/api/batches");
        if (date != null) request.param("businessDate", date.toString());
        if (currency != null) request.param("currency", currency);
        if (cursor != null) request.param("beforeSequence", cursor.toString());
        if (limit != null) request.param("limit", limit.toString());
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("batches").forEach(batch -> ids.add(batch.get("id").asText()));
        return ids;
    }
}
