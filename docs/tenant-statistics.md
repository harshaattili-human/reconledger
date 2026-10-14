# Tenant/currency statistics experiment

The [tenant query baseline](tenant-query-characterization.md) found a large joint
row-count overestimate. Adding a tenant/currency/sequence index alone did not fix
the PostgreSQL first-page scan. This follow-up separates index availability from
joint selectivity and allocation order. It changes no runtime query or migration.

## Protocol

`TenantStatisticsProbe` runs inside the existing characterization case on PostgreSQL.
H2 writes an explicit not-applicable receipt; it does not emulate PostgreSQL statistics.
Each allocation uses a temporary table cloned from the migrated `recon_batch` with
`LIKE ... INCLUDING ALL`, including independent identity and indexes. All experiment
DDL, headers and statistics are rolled back before returning the pooled connection.
Only temporary objects are changed; application tables are neither rebuilt nor tuned.

The AI-assisted synthetic fixture has 40,000 headers, not complete valid batches:
200 small-tenant XTS, 200 large-tenant XTS, 19,800 large-tenant USD, 19,800 foreign-tenant
XTS. The blocked order inserts those groups consecutively; the shuffled order uses
Java `Collections.shuffle` with seed 20261014 on the same group counts. Each order
gets its own empty clone and identity sequence.

Configurations run in fixed order: baseline, index, index plus multivariate
most-common-value (MCV) statistics, then MCV alone. The candidate index covers
`(tenant_id, currency, list_sequence)`; MCV covers `(tenant_id, currency)`.
Each configuration runs `ANALYZE`, records its elapsed time and table-plus-index
bytes, and records the MCV catalog items when present. Default statistics targets
are unchanged. Analyze samples and fixed configuration order are potential confounders.

Three requests (large XTS, large USD, small XTS) each exercise first and continuation
pages, limit 100/fetch 101. The 48 page/configuration observations each have five
warmups and 20 timed calls: 960 timed calls, 240 warmups. All fetched summaries,
including lookahead, must match an ordered reference list; rare second pages have
exactly 100 remaining rows. No timing or physical-index assertion determines success.

SQL uses the runtime projection, predicate order, `setObject` bindings and unnamed
statement policy, but targets a temporary clone on one connection. Timers include
JDBC execution and summary mapping, not repository/pool/HTTP overhead. A separate
JSON `EXPLAIN (ANALYZE, BUFFERS)` follows each page's timing block; it is not a plan
captured from each timed execution. Temporary-table plans use local buffers and
are not numerically interchangeable with earlier shared-buffer application-table runs.

## Reproduce

Configure a disposable PostgreSQL database as in [postgresql.md](postgresql.md), then:

```sh
./mvnw --batch-mode --no-transfer-progress -Dtest=TenantBrowseCharacterizationTest test
cat target/tenant-statistics.json
```

The disposable test role must be able to read `pg_statistic_ext_data` for the MCV
receipt. CI's isolated PostgreSQL service role has that access; do not grant extra
production privileges just to run this diagnostic.

CI prints the report. The cloned index definitions and all raw observations are
retained in [the October 14 report](../reports/tenant-statistics-2026-10-14.json).

PostgreSQL documents [cross-column statistics](https://www.postgresql.org/docs/16/planner-stats.html#PLANNER-STATS-EXTENDED),
the [MCV creation command](https://www.postgresql.org/docs/16/sql-createstatistics.html),
and [temporary table cloning](https://www.postgresql.org/docs/16/sql-createtable.html).

## Observed result — October 14, 2026

[Run 38574849165](https://github.com/harshaattili-human/reconledger/actions/runs/38574849165)
at `2a7d6bddad8cbe382036b51e6133c9fe5f8fcb2b` passed all 64 existing cases on each
H2 and PostgreSQL engine, plus packaged demo/body-bound/restart and secured-JAR
workflows. The matrix extends an existing case, not the JUnit case count. H2's
receipt explicitly says not applicable; the matrix ran only on PostgreSQL 16.15,
pgJDBC 42.7.11, Java 17.0.20.1, Linux amd64/Ubuntu 24.04. Local Maven lacked the
parent POM, so Java/database execution was hosted.

### Correct estimates were necessary but not sufficient in this fixture

The large tenant has exactly 200 XTS headers. The table below records the estimate
at the node below `Limit` and rows removed by filters in the separate first-page
EXPLAIN. Estimates concern the complete qualifying set, not the 101-row fetch.

| Configuration | Blocked estimate / filtered rows | Shuffled estimate / filtered rows | Rare-page access |
| --- | ---: | ---: | --- |
| Baseline | 10,102 / 39,600 | 10,098 / 19,508 | Global sequence index |
| Index only | 10,099 / 39,600 | 10,099 / 19,508 | Global sequence index |
| Index + MCV | 207 / 0 | 200 / 0 | Tenant/currency/sequence index |
| MCV only | 204 / 39,600 | 196 / 19,508 | Global sequence index |

The MCV catalog also distinguishes the joint large/XTS frequency (about 0.005)
from its independent-column base frequency (about 0.253). Catalog values are ordered
by physical column number: currency, then tenant. This supports the selectivity
hypothesis from the previous experiment, while the MCV-only scan shows why an
estimate correction is not itself a measured query fix.

For the blocked rare first page, baseline and index-only EXPLAIN each recorded
1,436 local-buffer reads. Index + MCV recorded nine local-buffer hits and no reads.
For shuffled allocations the corresponding baseline was 711 local hits, versus 100
with index + MCV. Local reads are buffer accounting, not proof of physical disk I/O;
temporary-table cache behavior differs from the application-table baseline.

### Timings and counterexamples

Every cell below is median / nearest-rank p95 in milliseconds for 20 sequential
JDBC calls after five warmups. These are within-run comparisons, not production or
repository latency, a cross-database ranking, or a general speedup claim.

| Page | Baseline | Index only | Index + MCV | MCV only |
| --- | ---: | ---: | ---: | ---: |
| Blocked large XTS first | 9.016 / 10.443 | 8.678 / 8.843 | 0.494 / 0.676 | 8.666 / 8.813 |
| Shuffled large XTS first | 3.338 / 3.415 | 3.373 / 3.420 | 0.390 / 0.420 | 3.334 / 3.438 |
| Blocked large USD first | 2.885 / 3.292 | 2.884 / 3.178 | 2.957 / 5.237 | 2.855 / 2.922 |

The blocked common-USD page still filtered 19,800 foreign rows with 722 local hits
in **all four** configurations, even after its estimate approached the true 19,800
matches. The index + MCV timing did not improve that case. In shuffled order the
same page filtered only 116 rows: physical allocation order remains important.
The shuffled rare continuation with index + MCV had a 0.483 ms median but 2.190 ms
p95; the full report preserves that tail rather than reporting only first-page wins.

The candidate index added 3,481,600 bytes to the clone in each order, measured by
`pg_total_relation_size` differences. This is fixture-specific storage, not a
production size forecast, and excludes the MCV system-catalog storage. Individual
ANALYZE calls ranged from 113.244 to 136.957 ms across the eight configurations;
they are not repeated maintenance benchmarks or an isolated MCV overhead estimate.
Write throughput, index-build cost and concurrent activity were not measured.

## Decision

Keep this as an experiment. No index/statistics migration or runtime query change
is shipped. Index + MCV is a candidate for the rare tenant/currency case, while
the common-currency blocked scan remains unresolved. Validate on ordinary application
tables across repeated, counterbalanced trials and representative tenant distributions
before adoption. Include startup/backfill/statistics-refresh behavior and write cost;
do not globally force plan choices. More tenants and changed distributions could
also exceed what the default MCV list captures.

Interview exercise: explain why correcting the total matching-row estimate does not
tell the planner where those rows occur in descending sequence order, and why an
index and joint statistics can help together when neither helps alone.
