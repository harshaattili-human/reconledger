# Query characterization

This check records how the two supported databases execute bounded reads at the
prototype's documented limits. It answers two narrow questions:

1. Does a 200-event audit page from a 10,000-event result history retain the
   `(result_id, sequence)` index access path?
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

Correct page contents, the final-page boundary and use of `review_event_result` are
assertions. Timing is deliberately observational: 5 warm-up calls precede 30 maximum-
batch samples and 40 audit-page samples, then the report records minimum, median,
p95 and maximum elapsed milliseconds. CI runner timing is not a stable pass/fail gate.

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
