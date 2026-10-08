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

For each of `auto`, `force_custom_plan` and `force_generic_plan`, it executes a fresh
prepared statement 20 times, consumes all projected columns and checks the 101
descending sequences against the fixture's expected first page plus lookahead.
It records execution times in order, then reads parameter types and custom/generic
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

## Observed result — October 8, 2026

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

## Limits

This isolates one connection, one rare currency, one page size, one distribution
and a fixed mode order. It does not reproduce the pool's entire prior statement
history or prove the cause of a previous run's wall-clock timing. It excludes HTTP,
concurrent traffic, application object construction and a realistic mix of currencies.
Forced modes are diagnostic controls, not application configuration recommendations.
An execution plan is not an API contract; no index name or timing is a pass/fail gate.
