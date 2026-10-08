# Query characterization

This check records how the two supported databases execute bounded reads at the
prototype's documented limits. It answers three narrow questions:

1. Does a 200-event audit page from a 10,000-event result history retain an
   indexed access path?
2. What work is observed when the service reconstructs a batch containing the
   maximum 500 records on each side?
3. How much scan work is needed for a batch page when rare matches are older
   than most nonmatching batches?

`QueryCharacterizationTest` creates only synthetic rows. Its audit fixture has three
results with 10,000 events each, inserted round-robin so the global sequence contains
events for other results between the target's events. The target page starts after
version 5,000, returns 200 rows and has more history. A second page check starts at
version 9,800 and confirms that exactly 200 remaining rows produce a null cursor.
The fixture updates each result to the state and version represented by its last event.

The batch fixture uses 500 matching references on each side: 1,000 source rows and
500 results. The service creates it through its normal validation, normalization,
engine and transaction path, then reloads it through `LedgerRepository.getBatch`.

## Reproduce

The normal verification command writes `target/query-characterization.json`:

```bash
./mvnw --batch-mode --no-transfer-progress verify
cat target/query-characterization.json
```

That report includes database, driver, Java and architecture versions; query plans;
serialized maximum-batch response size; and warm in-process read timing. The CI jobs
print the report for both H2 and PostgreSQL. Use the environment setup in
[the PostgreSQL guide](postgresql.md) to reproduce that engine locally.

Correct page contents, the final-page boundary and indexed audit access are assertions.
H2 must use `review_event_result`. PostgreSQL may choose that composite index or the
ordered `review_event_pkey`: with this round-robin fixture the primary key can satisfy
`ORDER BY sequence` while filtering intervening results. The recorded plan makes that
tradeoff visible instead of treating one planner choice as part of the API contract.

Timing is deliberately observational: 5 warm-up calls precede 30 maximum-batch
samples and 40 audit-page samples, then the report records minimum, median, p95 and
maximum elapsed milliseconds. CI runner timing is not a stable pass/fail gate.

## Hosted observation — October 6, 2026

[Run 37470641915](https://github.com/harshaattili-human/reconledger/actions/runs/37470641915)
executed the same 35 tests against H2 2.3.232 and PostgreSQL 16.15 at source
`8049376e00c4f19ee95231212a5c5539b0aa8d7f`. Both jobs also passed the packaged
HTTP demo and application-process restart check. The runner used Temurin Java
17.0.20.1 on Linux amd64.

| Warm in-process read | Fixture | Samples | H2 median / p95 | PostgreSQL median / p95 |
| --- | --- | ---: | ---: | ---: |
| Maximum batch reconstruction | 500 left + 500 right; 500 results | 30 | 1.252 / 2.757 ms | 2.294 / 4.192 ms |
| Audit page | 200 rows after event 5,000; 3 x 10,000 events | 40 | 0.101 / 0.159 ms | 0.527 / 0.659 ms |

The serialized maximum-batch response was 153,701 bytes on each engine. PostgreSQL's
batch plans used a bitmap index scan for the 1,000 source rows and an index scan for
the 500 results. H2 reported its batch constraint indexes.

For the audit page, H2 selected `review_event_result`. PostgreSQL selected the global
`review_event_pkey`, used the sequence cursor as its index condition, filtered 402
round-robin rows from the other results, and returned the 201-row lookahead with 17
shared-buffer hits; its reported `EXPLAIN ANALYZE` execution time was 0.091 ms.

The first PostgreSQL run failed because the test required the composite index by name,
even though the plan was already indexed and bounded. The corrected assertion accepts
either valid PostgreSQL index choice while still rejecting a sequential scan. This is
a test-contract correction, not an application performance fix.

## Sparse batch filters — October 8, 2026

The list fixture inserts 20,000 synthetic headers directly through JDBC, with no
source or result rows. It isolates header browsing, not valid full-batch creation or
ingestion throughput. The first 200 allocations share date `2090-01-01` and currency
`XTS`; the next 19,800 use the following date and `USD`. Other suite fixtures remain
in the database, so 20,000 is the added fixture size, not the total table cardinality.
The fixture removes its own headers afterward and refreshes database statistics
before measuring.

For date-only, currency-only and combined filters, the check compares two 100-row
pages against the full descending sequence list. It verifies the lookahead cursor
and exactly-full final page. A combined date/`ZZZ` filter checks no matches. The
report records equivalent SQL plans for first and continuation reads and 30 warm
first-page repository calls after five warmups. It does not assert an index name or
timing threshold. These are repeated warm calls; elapsed time alone should not be
read as evidence of reduced scan work. The plans record scan work separately.

Before V3, [run 37783586177](https://github.com/harshaattili-human/reconledger/actions/runs/37783586177)
at source `dc88b0274a05f7f56f5cd9f0f334026d715d72a7` passed all 42 cases on each
engine and both HTTP/restart checks. PostgreSQL's currency-only first page used a
backward `recon_batch_list_sequence` scan, removed 19,800 rows by filter and used
682 shared-buffer hits to return 101 rows including lookahead. H2 reported a scan
count of 19,901. The combined filter already used the date/currency/sequence index.

V3 adds `(currency, list_sequence)` to support the independently optional currency
filter. This costs another index entry on each batch insertion and additional disk
space. It does not change the API, ordering or cursor semantics. Index creation is
an ordinary Flyway migration, not a promise of lock-free online deployment.

After V3, [run 37783840468](https://github.com/harshaattili-human/reconledger/actions/runs/37783840468)
at `0580d527154a6333ac24bc27d9831caa026dbe24` passed the same 42 tests on each
engine, including retained-data migration, plus both packaged HTTP/restart checks.
Both runs used H2 2.3.232, PostgreSQL 16.15, Java 17.0.20.1 and Linux amd64.

| Currency-only first page, 100 rows plus lookahead | Before V3 | After V3 |
| --- | ---: | ---: |
| PostgreSQL EXPLAIN shared-buffer hits | 682 | 6 |
| PostgreSQL EXPLAIN rows removed by filter | 19,800 | No filter node |
| PostgreSQL EXPLAIN execution time | 3.052 ms | 0.065 ms |
| H2 EXPLAIN scan count | 19,901 | 201 |
| PostgreSQL repository calls, median / p95 | 3.306 / 6.148 ms | 5.741 / 6.053 ms |
| H2 repository calls, median / p95 | 0.170 / 0.218 ms | 0.162 / 0.196 ms |

PostgreSQL used the new currency/sequence index for both pages; H2 also selected it.
The explicit EXPLAIN plans show reduced scan work on this distribution. The repeated
repository timings do **not** establish a latency improvement: PostgreSQL's median
rose in the separate after run. Driver/prepared-plan behavior, pool effects and runner
variation were not isolated. Those observations remain in the report instead of
being replaced with only the favorable EXPLAIN time. A follow-up should compare the
actual prepared execution plans and repeated trials before making latency claims.

## Interpretation limits

This is a single-process, sequential micro-measurement on generated data. It does
not model concurrent reviewers, network latency, connection-pool saturation, cache
eviction, table bloat, replicas or long-running production history. H2 and PostgreSQL
run in separate environments, so their elapsed times are not a database ranking.
`EXPLAIN ANALYZE` adds its own measurement work.

The maximum batch remains deliberately bounded and is assembled in memory. Audit
pagination remains a live read rather than a frozen snapshot. Results from this check
can justify retaining or revisiting those constraints; they are not a throughput
claim or service-level objective.

PostgreSQL references: [`EXPLAIN`](https://www.postgresql.org/docs/16/sql-explain.html)
and [index use for `ORDER BY` with `LIMIT`](https://www.postgresql.org/docs/16/indexes-ordering.html).
