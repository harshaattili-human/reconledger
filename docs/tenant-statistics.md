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

CI prints the report. Results are pending the first run. No candidate is promoted
to the application based on fixture construction alone. Further work would need
representative workloads, repeated trials, write/maintenance costs and retained-data
migration planning before a runtime change.

PostgreSQL documents [cross-column statistics](https://www.postgresql.org/docs/16/planner-stats.html#PLANNER-STATS-EXTENDED),
the [MCV creation command](https://www.postgresql.org/docs/16/sql-createstatistics.html),
and [temporary table cloning](https://www.postgresql.org/docs/16/sql-createtable.html).
