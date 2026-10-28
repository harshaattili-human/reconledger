# Ordinary-table index/statistics experiment

The earlier [temporary-table probe](tenant-statistics.md) found that a joint
tenant/currency MCV statistic and index helped a rare-currency first page together,
while neither alone fixed it. Temporary tables use local buffers and do not undergo
autovacuum. That result cannot establish the behavior or costs of ordinary tables.

`TenantRuntimeStatisticsTest` compares fresh ordinary, logged `LIKE recon_batch
INCLUDING ALL` clones. It verifies `relpersistence=p`, then rolls back each clone,
its sequence, indexes and statistics. It never applies a candidate migration or
changes application DDL, rows, sequence allocation or planning settings. Original
application row count, index definitions and sequence state are compared afterward.
Autovacuum is disabled only on each disposable clone to keep maintenance explicit.

```sh
# With the disposable PostgreSQL test environment in postgresql.md:
./mvnw -Dtest=TenantRuntimeStatisticsTest test
```

The same test records "not applicable" on H2. PostgreSQL execution is part of its
normal verification job, with a 15-minute job bound. The complete raw report is
`target/tenant-runtime-statistics.json`; CI retains it for 30 days, including an
incomplete report when a trial fails. No latency or index-name threshold gates CI.

## Fixed protocol

Each clone contains 40,000 AI-assisted synthetic headers, not complete application
batches. The small tenant owns 200 XTS rows. The large tenant owns 200 XTS followed
by 19,800 USD rows. The remaining 19,800 XTS rows belong either to one foreign tenant
(three tenants total) or 198 tenants with 100 rows each (200 total). Blocked allocation
and a deterministic MD5 ordering of logical ordinals are tested separately. IDs are
deterministic synthetic UUID text. These are authored distributions, not customer data
or a claim that the mixtures represent production traffic.

For each of four tenant-count/allocation scenarios, compare baseline, index only,
index plus MCV, and MCV only. Four cyclic orders put each configuration in each
position once. Each trial starts from a new clone, avoiding leftover indexes or
statistics from the preceding configuration. That is 64 clones. Every trial measures
first/continuation pages for large-XTS, large-USD and small-XTS: 384 page observations,
three warmups and ten timed calls each (1,152 warmups plus 3,840 timed calls).

The SQL matches the runtime projection, predicate order, bound `setObject` parameters
and currency-specific statement policy. It returns 101 rows to support a 100-row page.
All rows are compared with a reference obtained by reading the full clone and filtering
it in Java. This warms data deliberately. Complete ordered samples and separate
post-timing `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` plans are retained. Plans are not
collected for every timed call. p50/p95 use nearest rank; with ten samples p95 is the
maximum, so report trial spread instead of treating it as a stable tail estimate.

Each trial also records initial header-load time, candidate index build time/bytes,
MCV payload bytes/items, full relation size, ANALYZE time, then an additional
1,000-header insert and another ANALYZE. These are sequential statement durations,
not commit latency, durable ingestion throughput or isolated WAL cost: clones are
created and rolled back in one transaction. MCV payload size excludes catalog/index
overhead. Configuration order is counterbalanced, but cache, PostgreSQL sampling,
JIT, shared-host variation and other order effects are not fully isolated.

Ordinary clones use shared buffers, but this is still a direct JDBC, single-connection
experiment rather than the repository/pool/HTTP path. There is no concurrent writing,
vacuum lifecycle, long history, production tenant distribution or SLO evidence.
No index/statistics candidate should be promoted solely because this probe is faster.
Preserve slow/common-currency cases and differences between repetitions.

Local whitespace checks pass; the Boot parent remains unavailable in the offline
Maven cache. Java/database observations will be recorded after hosted execution.

Primary references: PostgreSQL 16 [CREATE TABLE](https://www.postgresql.org/docs/16/sql-createtable.html),
[ANALYZE](https://www.postgresql.org/docs/16/sql-analyze.html) and
[extended statistics](https://www.postgresql.org/docs/16/planner-stats.html#PLANNER-STATS-EXTENDED).

Interview exercise: why can a temporary-table result change on an ordinary table,
and why do a faster read plan and low insert-statement time not establish a safe migration?
