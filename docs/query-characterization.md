# Query characterization

This check records how the two supported databases execute bounded reads at the
prototype's documented limits. It answers two narrow questions:

1. Does a 200-event audit page from a 10,000-event result history retain an
   indexed access path?
2. What work is observed when the service reconstructs a batch containing the
   maximum 500 records on each side?

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
