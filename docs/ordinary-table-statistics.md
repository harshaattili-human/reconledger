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

## Observed results — October 28, 2026

Implementation `89e39d611bfebf4df6abd1576574193cbc7250df` passed all five jobs in
[run 40702276651](https://github.com/harshaattili-human/reconledger/actions/runs/40702276651).
The PostgreSQL experiment completed all 64 trials, 384 page observations and
3,840 timed calls, with application-state and clone-cleanup assertions passing.
The candidate index plus MCV was selected for the large-tenant rare-XTS first page
in all 16 corresponding trials; index alone and MCV alone retained the global
sequence scan. That supports the earlier narrow finding on shared buffers; it does
not establish a generally beneficial migration.

Ranges below span **four trial medians**, each based on ten timed calls, in ms:

| Tenants / allocation | Baseline rare-XTS first page | Index + MCV rare-XTS first page |
| --- | ---: | ---: |
| 3 / blocked | 2.928–4.196 | 0.163–0.260 |
| 3 / shuffled | 1.734–1.822 | 0.172–0.180 |
| 200 / blocked | 2.946–3.246 | 0.199–0.212 |
| 200 / shuffled | 1.709–1.856 | 0.188–0.207 |

In repetition zero, blocked scans removed 39,600 rows and touched 1,355/1,371 shared
blocks for three/200 tenants; the combination removed zero rows and touched seven.
Shuffled scans removed 20,329 rows and touched 698/700 blocks versus 97 with the
combination. These were shared **hits**, with zero shared reads in those plans;
there is no physical-disk performance claim. Full-match estimates near 10,100 became
195/204 for an actual total of 200 (the LIMIT returns 101). The ten-call p95 for
the 200-tenant blocked combination still reached 1.104 ms in one repetition.

The common-USD first page remained a counterexample. For three blocked tenants,
baseline trial medians were 1.404–1.632 ms versus 1.452–2.834 with the combination;
its worst ten-call p95 was 3.203 versus 1.997 ms. With 200 shuffled tenants the
combination's medians were 0.176–0.197 versus 0.149–0.157 ms. Do not summarize the
rare-page gain as an improvement for all tenant browsing.

The candidate index occupied 1,826,816 bytes per clone. Across index-bearing trials,
build duration ranged 25.922–175.960 ms. MCV held four entries for three tenants and
100 for 200 tenants, with 215–1,254 payload bytes; this does not prove coverage for
arbitrary tenant distributions. Initial ANALYZE ranged 54.906–139.528 ms. The
1,000-row insert statement ranged 11.292–26.673 ms for baseline and 12.660–116.943 ms
for the combination. These adverse observations are retained, not attributed to a
single cause; the experiment does not isolate commit/WAL or production write cost.

The [compressed raw JSON](../reports/ordinary-table-statistics-2026-10-28.json.gz)
retains every trial, sample, full plan and MCV entry beyond artifact expiry.
Decompressed SHA-256 is
`523e7f4987b08e0096b68d3f524d3fb99e0bf52eca453d8adea27c5f99c839c7`.
Reproduce the full per-trial summary with:

```sh
python3 scripts/summarize_statistics.py reports/ordinary-table-statistics-2026-10-28.json.gz
```

Environment: PostgreSQL 16.15, pgJDBC 42.7.14, Java 17.0.21, Linux amd64 on Ubuntu
24.04; `default_statistics_target=100`, `random_page_cost=4`, plan cache auto, JIT on.
The raw report preserves other settings. H2 2.3.232 and PostgreSQL each passed the
same 119 Java cases, zero failures/errors/skips; this one case executes the probe
only on PostgreSQL. Existing 70 browser executions, package/restart/key/HTTPS/Boot 3
upgrade, restore and audit checks passed. Java audit inventoried 73 libraries with
zero advisory entries in the dated snapshot, not a vulnerability-free assertion.
The candidate remains experimental. Concurrent repository/pool traffic, committed
write costs, statistics refresh under changing ownership and deployment tradeoffs
are required before adoption.
