# Mixed requests on one prepared statement

The isolated [currency/page-size matrix](prepared-plans.md) gave every request
shape its own statement history. A reused connection can see different parameter
values on the same statement. This diagnostic tests whether its initial requests
change later automatic plan selection.

## Protocol

`MixedPreparedPlanProbe` uses the existing synthetic fixture: 200 XTS headers
allocated before 19,800 USD headers, plus other test-suite rows. It uses one checked-out
JDBC connection, the repository's currency-only projection, and the same string and
integer bindings. It creates no new application data or indexes.

Each history starts with ten identical requests, using one of three primers:

- XTS, page limit 100.
- USD, page limit 100.
- XTS, page limit 1.

Each then executes this identical six-request sequence three times:
`USD/100, XTS/100, USD/50, XTS/50, USD/1, XTS/1`.
Each limit includes one extra fetched row for lookahead. There are 28 calls per
history: ten primer calls and 18 mixed calls. Each primer runs once in `auto` and
once in `force_custom_plan`, giving six independent statement histories and 168
correctness-checked calls. These extend the existing characterization test, not the
test-case count. The forced mode is a diagnostic control, not an application setting.

A different fixed SQL comment identifies each history. Within a history the same
PreparedStatement remains open. Every result sequence list must match an ordered
reference read for that currency and limit. No concurrent writer changes the fixture.

After each call, the probe reads the target statement's counters from
[`pg_prepared_statements`](https://www.postgresql.org/docs/16/view-pg-prepared-statements.html).
Once a named statement appears, exactly one custom or generic count must advance
per call, and the statement name must remain unchanged. Before it appears, the
report says `unnamed-unobserved`; it does not guess a plan type. The query time
excludes the catalog inspection and correctness assertion. The catalog read adds
inter-request work, so these are instrumented sequential observations, not request
latency or throughput measurements.

No EXPLAIN is run between these calls. After all 28 calls, one additional
`EXPLAIN (ANALYZE, BUFFERS)` executes an XTS limit-1 request. That plan is labeled
separately and is not retroactively assigned to earlier executions. The original
session planning mode is restored before returning the connection to the pool.

## Reproduce

Follow the disposable database setup in [postgresql.md](postgresql.md) and run:

```bash
./mvnw --batch-mode --no-transfer-progress verify
cat target/query-characterization.json
```

The currency filter's `mixedPreparedExecution` field contains the primer, mode,
every request's parameters/timing/counters, and the final XTS plan. H2 continues to
run the shared contract suite; this PostgreSQL-specific catalog diagnostic is omitted.

## Limits

This controls initial request history on one connection and one deliberately skewed
fixture. It does not model connection-pool scheduling, concurrent writes, realistic
currency frequency, other filter combinations or long-running plan adaptation.
It does not isolate planning CPU, index maintenance or storage costs. The fixed
mode/history order can affect cache warmth and timings. No timing or physical index
name is a pass/fail gate, and no runtime mitigation is selected by this probe alone.
