# Prepared currency-query investigation

The sparse-filter experiment reduced scan work in a one-off `EXPLAIN`, but repeated
repository calls had mixed timings. This check inspects a driver-created prepared
statement to test whether plan reuse helps explain that difference.

## Protocol

The probe runs inside the existing PostgreSQL characterization fixture: 200 rare
`XTS` headers precede 19,800 newer `USD` headers, alongside other test-suite rows.
It checks out one connection and runs the currency-only list query with the same
projection and JDBC string/integer bindings as the repository. A fixed SQL comment
distinguishes each probe statement in the session catalog. No table or index is
changed by the probe.

The current probe covers rare `XTS` and common `USD`, with page limits 1, 50 and
100. Each of these six combinations runs in `auto`, `force_custom_plan` and
`force_generic_plan`: 18 fresh prepared statements, 20 executions each (360 timed
calls). Each statement consumes all projected columns and checks the descending
sequence list including one lookahead row against an ordered reference read without
`LIMIT`. The common-currency reference includes any USD headers from other tests;
19,800 is the added fixture count, not the full table's currency cardinality.
Each combination records currency, requested page limit, fetched row count and
execution times in order, then reads parameter types and custom/generic
counters from `pg_prepared_statements`. Finally, it runs `EXPLAIN (ANALYZE, BUFFERS)`
on that named statement. The counters are captured before this additional execution.
The original session mode is restored before returning the connection to the pool.

The [pgJDBC documentation](https://jdbc.postgresql.org/documentation/server-prepare/)
describes when the driver starts using named statements; its default threshold is
five executions. PostgreSQL separately chooses between
[custom and generic plans](https://www.postgresql.org/docs/16/sql-prepare.html).
The [session view](https://www.postgresql.org/docs/16/view-pg-prepared-statements.html)
reports how often each kind was used. These are separate decisions, so the number
of named-statement executions can be less than the 20 JDBC executions.

## Reproduce

Use the disposable PostgreSQL setup in [postgresql.md](postgresql.md), then run:

```bash
./mvnw --batch-mode --no-transfer-progress verify
cat target/query-characterization.json
```

Look under the currency filter's `preparedExecution` field. The H2 job continues to
run the shared behavior suite and ordinary query characterization; this PostgreSQL
catalog probe does not run on H2.

## Initial rare-currency result — October 8, 2026

This historical run used only XTS with a 100-row page. The expanded matrix is
reported separately below; its measurements must not be mixed with this run.

[Run 37785169114](https://github.com/harshaattili-human/reconledger/actions/runs/37785169114)
at source `c73eb4433e8b7c3807c332e9ac7012df163f2cbc` passed all 42 tests on each
database plus both HTTP/restart checks. The probe ran on PostgreSQL 16.15 with
pgJDBC 42.7.11, Java 17.0.20.1 and Linux amd64. Bound parameter types were
`character varying` and `integer` in all three modes.

| Mode | Custom / generic counts before EXPLAIN | Observed named-statement plan | Buffer hits | Rows removed by filter |
| --- | ---: | --- | ---: | ---: |
| auto | 5 / 11 | Backward global sequence index scan | 681 | 19,800 |
| force_custom_plan | 16 / 0 | Backward currency/sequence index scan | 6 | No filter node |
| force_generic_plan | 0 / 16 | Backward global sequence index scan | 681 | 19,800 |

In automatic mode, executions 1–9 took 0.229–0.442 ms; execution 10 took 2.497 ms,
and executions 10–20 ranged from 1.364 to 2.502 ms. The catalog counters and the
subsequent plan show that this run switched to generic planning. Its estimate was
6,713 matches, while the rare fixture has 200. The plan expected to find a limited
page cheaply by scanning the global sequence, but every rare match was older than
19,800 nonmatching rows. Forced custom execution took 0.126–0.364 ms; forced generic
execution took 1.317–1.710 ms. These are the 20 raw calls in each mode, including
initial executions, not warmed production latency percentiles.

This reproduces a plan-reuse mechanism consistent with the earlier discrepancy.
It does not retroactively identify the exact plans on every pooled connection in
the previous run. The V3 index is useful to the custom plan; merely adding it does
not guarantee that automatic planning will use it for a skewed parameter.

No runtime setting was changed. Forcing custom planning trades plan reuse for
per-execution planning and needs evaluation with common currencies, different limits
and representative traffic before adoption. The report retains all raw timings,
parameter types, counters and plan lines in `target/query-characterization.json`.

## Currency and page-size matrix — October 8, 2026

[Run 37869485412](https://github.com/harshaattili-human/reconledger/actions/runs/37869485412)
at `16d7ba574324bfb180f591a473de5fc07223b14f` passed all 42 tests on each
database and both packaged HTTP/restart checks. This extends the existing
characterization case; 360 timed calls are not 360 additional test cases.
PostgreSQL 16.15, pgJDBC 42.7.11 and Java 17.0.20.1/Linux amd64 were unchanged
from the initial investigation. All 18 combinations returned the expected sequences.

Automatic mode produced these observations after 20 executions per combination:

| Currency | Page limit | Custom / generic counts | Final EXPLAIN index | Buffer hits | Rows removed by filter |
| --- | ---: | ---: | --- | ---: | --- |
| XTS | 1 | 16 / 0 | Currency/sequence | 3 | None reported |
| XTS | 50 | 16 / 0 | Currency/sequence | 5 | None reported |
| XTS | 100 | 5 / 11 | Global sequence | 682 | 19,800 |
| USD | 1 | 16 / 0 | Global sequence | 3 | None reported |
| USD | 50 | 16 / 0 | Global sequence | 5 | None reported |
| USD | 100 | 16 / 0 | Global sequence | 6 | None reported |

For rare XTS, forced custom plans used the currency index at every page size;
forced generic plans scanned past 19,800 nonmatches at every size. Across their
60 calls each (20 per page size), forced custom client times ranged from
0.258–0.595 ms and forced generic times from 2.909–3.737 ms. These ranges include
initial executions and are not latency percentiles. For common USD, both forced
modes used the global sequence index with 3, 5 and 6 buffer hits at the three
limits; the final plans reported no removed rows. Common rows are the newest
allocations in this fixture, making that scan cheap.

The final generic EXPLAIN estimated 671 rows at its Limit node for all page sizes,
while custom plans used the bound fetch sizes 2, 51 and 101. PostgreSQL compares
estimated custom and generic costs, so page size as well as currency distribution
can affect automatic plan selection. The small-page custom cost stayed below the
generic estimate in this run. The large rare-page custom estimate exceeded it,
even though the actual generic scan was more expensive.

No global planning override follows from these results. The next relevant check
is to vary currency and limit on the **same** prepared statement, including changing
the initial parameter order. Real pooled statement reuse can mix request shapes;
these isolated histories cannot establish that behavior. Concurrent writes and
index maintenance costs remain unmeasured. Local Maven execution was blocked by
an uncached parent POM; compilation and database execution evidence came from CI.

The follow-up [mixed-request probe](mixed-prepared-plans.md) tests shared statement
history with different initial requests.

## Limits

This isolates one connection and one allocation distribution, with a fixed currency,
page-size and mode order. Each combination starts its own statement history; it does
not alternate currencies on one prepared statement. It does not reproduce the pool's entire prior statement
history or prove the cause of a previous run's wall-clock timing. It excludes HTTP,
concurrent writes, application object construction and a realistic traffic mix.
The EXPLAIN planning time belongs to that extra execution, not a total of planning
cost across the preceding calls. Raw client timings include planning where required,
but do not isolate that cost from execution, transfer and field decoding.
Forced modes are diagnostic controls, not application configuration recommendations.
An execution plan is not an API contract; no index name or timing is a pass/fail gate.
