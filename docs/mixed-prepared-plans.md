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
history: ten primer calls and 18 mixed calls. Each primer runs in `auto`,
`force_custom_plan`, and the query-scoped policy used by the repository. This gives
nine independent histories and 252 correctness-checked calls. These extend the
existing characterization test, not the test-case count. Forced custom planning is
a diagnostic control, not an application setting.

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
`EXPLAIN (ANALYZE, BUFFERS)` executes an XTS limit-1 request. Named histories use
`EXPLAIN EXECUTE`; the query-scoped history applies the same statement policy to a
parameterized EXPLAIN and must remain absent from the prepared-statement catalog.
That plan is labeled separately and is not retroactively assigned to earlier
executions. The original session planning mode is restored before returning the
connection to the pool.

## Query-scoped candidate

`BatchBrowseStatementPolicy` unwraps only PostgreSQL batch-list statements to
pgJDBC's `PGStatement` and sets their prepare threshold to zero. The driver's
[server-prepare documentation](https://jdbc.postgresql.org/documentation/server-prepare/)
defines zero as disabling server-side named preparation and shows that the threshold
can be set on one statement. Other repository statements, connections, and PostgreSQL
sessions keep their configured defaults. H2 statements do not expose that extension
and keep the portable JDBC path.

The browse query still uses a `PreparedStatement` with bound values. This avoids the
generic named-plan history observed for skewed parameters; it does not interpolate
values into SQL or force a database-wide custom-plan setting. The tradeoff is repeated
parse/analysis/planning work and loss of other named-statement optimizations for this
query. The experiment records that cost rather than assuming the mitigation wins.

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

The October 9 increment kept runtime behavior unchanged and justified comparing a
query-scoped mitigation against automatic planning. The follow-up below records that
comparison. Planning cost, concurrent writes and different allocation distributions
still need evidence; forcing every query on every connection to use custom planning
remains broader than this finding.

Interview exercise: why did the same XTS/1 request select a custom plan in an
isolated history but a generic plan after the XTS/100 primer? Explain why running
EXPLAIN between requests would change the experiment.

## Query-scoped result — October 10, 2026

[Run 38055419493](https://github.com/harshaattili-human/reconledger/actions/runs/38055419493)
at source `6f912d95dd997002c64b0bdfb37e29ef3ee506d7` passed 43 test cases on
each database and both packaged HTTP/application-restart checks. The environment
was PostgreSQL 16.15, pgJDBC 42.7.11, Java 17.0.20.1 and Linux amd64. The added
unit case checks both the PostgreSQL wrapper and portable JDBC paths.

The expanded probe ran 252 calls: three primers, three modes, and 28 calls per
history. All result sequences matched. Every one of the 84 query-scoped calls
remained absent from `pg_prepared_statements`; named-statement histories were still
observed for the automatic and forced-custom controls.

| XTS/100 primer | Automatic baseline | Forced-custom control | Query-scoped policy |
| --- | --- | --- | --- |
| Named plans after 28 calls | 5 custom / 19 generic | 24 custom / 0 generic | None |
| Nine rare mixed calls, range | 2.942–5.997 ms | 0.223–0.360 ms | 0.255–0.363 ms |
| Nine rare mixed calls, median | 4.871 ms | 0.287 ms | 0.321 ms |
| Final XTS/1 EXPLAIN | Global sequence; 677 hits; 19,800 filtered | Currency/sequence; 3 hits | Currency/sequence; 3 hits |

The query-scoped policy also kept the final XTS/1 plan on the currency index after
the USD/100 and XTS/1 primers. Across those two mixed tails its medians were 0.307 ms
and 0.346 ms, compared with 0.282 ms and 0.291 ms in automatic mode. This small
same-run difference is consistent with repeated planning overhead; it is not a
general latency estimate. For the large rare primer, common-currency calls had
medians of 0.303 ms automatic and 0.308 ms query-scoped, while the rare-query generic
scan dominated the automatic history.

The separate final XTS/1 EXPLAIN after the large rare primer reported 0.010 ms
planning and 2.992 ms execution for its generic baseline, versus 0.070 ms planning
and 0.021 ms execution for the unnamed query-scoped call. Those are single extra
executions, not totals across the history. The repository's existing 30-sample rare
currency page measurement reported median 0.683 ms and p95 0.828 ms in this run;
it is sequential synthetic observation, not an SLO or production benchmark.

Based on this bounded fixture, the query-scoped policy is retained: it removes the
observed history-dependent generic scan without changing pooled-session settings.
It may cost more on distributions where automatic named plans are consistently good.
Re-evaluate it with representative data before treating the policy as permanent.

## Limits

This controls initial request history on one connection and one deliberately skewed
fixture. It does not model connection-pool scheduling, concurrent writes, realistic
currency frequency, other filter combinations or long-running plan adaptation.
It does not isolate planning CPU, index maintenance or storage costs. The fixed
mode/history order can affect cache warmth and timings. No timing or physical index
name is a pass/fail gate. The selected mitigation is deliberately narrow and remains
conditional on this evidence rather than a production guarantee.
