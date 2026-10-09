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

## Observed result — October 9, 2026

[Run 37935894837](https://github.com/harshaattili-human/reconledger/actions/runs/37935894837)
at source `d598b062dc38257b80881e7a4fd80a7dea39b4b7` passed the same 42 tests
on H2 2.3.232 and PostgreSQL 16.15, plus both packaged HTTP demos and application
restart checks. The probe used pgJDBC 42.7.11, Java 17.0.20.1 and Linux amd64.
All 168 result lists matched their references. The first four calls in each history
had no named statement; the remaining 24 had stable names and varchar/integer
parameter types. Counters advanced exactly once per observed execution.

| Initial ten requests | Mode | Custom / generic counts after 28 calls | Plan kinds during 18 mixed calls | Final XTS/1 EXPLAIN |
| --- | --- | ---: | --- | --- |
| XTS/100 | auto | 5 / 19 | All generic | Global sequence index; 677 buffer hits; 19,800 rows filtered |
| USD/100 | auto | 24 / 0 | All custom | Currency/sequence index; 3 buffer hits; no filter node |
| XTS/1 | auto | 24 / 0 | All custom | Currency/sequence index; 3 buffer hits; no filter node |
| Each of the three primers | force_custom_plan | 24 / 0 per history | All custom | Currency/sequence index; 3 buffer hits; no filter node |

Automatic planning switched to generic on call 10 after the large rare-currency
primer. It stayed generic across all 18 mixed calls. Thus the earlier isolated
finding that XTS/1 and XTS/50 used custom plans does not establish what those same
parameters do on a reused statement. The other two primers kept the identical
mixed sequence custom throughout this bounded observation window. This does not
prove either choice persists indefinitely or survives statistics/schema changes.

The nine rare-currency calls in the mixed tail after the XTS/100 primer took
1.443–2.078 ms in automatic mode, versus 0.088–0.241 ms for the same nine requests
in the forced-custom control. These are min/max of nine instrumented sequential
calls, across page sizes 100, 50 and 1, not p95s, production latency or a general
speedup. The final extra XTS/1 EXPLAIN reported 1.625 ms execution in automatic mode
and 0.012 ms in the forced-custom control; those are separate executions.

The catalog counters establish plan-kind selection per named call. Only the final
EXPLAIN establishes its specific access path and scanned rows; it is not a trace
of every earlier plan. Local Maven could not resolve an uncached parent POM in
offline mode, so compilation and database execution were verified in hosted CI.

## Decision and next check

Keep runtime behavior unchanged in this increment. The result justifies comparing
a query-scoped mitigation against automatic planning across these histories. Any
candidate must retain bound parameters, avoid leaking session settings to the pool,
and preserve result/cursor behavior on both databases. Planning cost, concurrent
writes and different allocation distributions still need evidence; forcing every
query on every connection to use custom planning is broader than this finding.

Interview exercise: why did the same XTS/1 request select a custom plan in an
isolated history but a generic plan after the XTS/100 primer? Explain why running
EXPLAIN between requests would change the experiment.

## Limits

This controls initial request history on one connection and one deliberately skewed
fixture. It does not model connection-pool scheduling, concurrent writes, realistic
currency frequency, other filter combinations or long-running plan adaptation.
It does not isolate planning CPU, index maintenance or storage costs. The fixed
mode/history order can affect cache warmth and timings. No timing or physical index
name is a pass/fail gate, and no runtime mitigation is selected by this probe alone.
