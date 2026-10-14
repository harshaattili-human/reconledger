# Tenant-filtered batch browsing

The tenant boundary changed every batch-list query. Earlier
[prepared-plan experiments](mixed-prepared-plans.md) used unscoped SQL, so their
plans do not establish how the current tenant predicate behaves.
`TenantBrowseCharacterizationTest` measures the current repository path separately.
The final change adds measurement coverage only. An index candidate was tested and
rejected; the application query, released schema and connection settings are unchanged.

## Fixture and protocol

One AI-assisted synthetic fixture inserts 40,000 **headers only**, in this allocation
order:

| Owner | Headers | Currency | Business date |
| --- | ---: | --- | --- |
| Small tenant | 200 | XTS | 2091-01-01 |
| Large tenant | 200 | XTS | 2091-01-01 |
| Large tenant | 19,800 | USD | 2091-01-02 |
| Foreign tenant | 19,800 | XTS | 2091-01-02 |

These are not complete application batches or ingestion measurements. Other tests
can leave records in the database; the report records total table rows separately.
Tenant identifiers and header IDs are fresh per run. Cleanup removes only fixture
IDs, and the test restores the caller's security context in `finally`.

The test calls the actual `LedgerRepository` with a secured `TenantAccess` and
synthetic authenticated context. This bypasses token verification deliberately for
query measurement; [secured HTTP tests](tenants.md) cover that boundary.
For both small and large tenants it checks no filter, date, currency, combined
filters and an absent currency. It also checks an empty tenant. The expected rows
come from a tenant-owned fixture reference list filtered in Java. Every measured
call must match full summaries, descending order and the lookahead cursor.

Pages have limit 100 (SQL fetch 101). First and continuation pages are checked
where matches exceed 100. The 200-match cases end on an exactly full final page;
the large unfiltered continuation still has another page. Each of the 19 measured
pages has five warmups and 30 sequential timed repository calls. Assertions run
after each timer stops. The report retains ordered samples and nearest-rank median
and p95; no timing or physical-index-name threshold controls pass/fail.

After timing each page, a separate `EXPLAIN ANALYZE` uses the runtime projection,
predicate order, `setObject` bindings and `BatchBrowseStatementPolicy`. PostgreSQL
also records buffers. This extra execution is not a captured plan from each timed
call. Duplicated diagnostic SQL must be updated when the application query changes.
No `EXPLAIN` is interleaved into the timed calls. `ANALYZE` runs after fixture creation.

## Reproduce

```sh
./mvnw --batch-mode --no-transfer-progress -Dtest=TenantBrowseCharacterizationTest test
cat target/tenant-browse-characterization.json
```

For PostgreSQL, set the disposable-database variables in
[postgresql.md](postgresql.md) first. CI runs this case as part of `verify` on both
engines and prints the report. A stale report is removed at test start.

## October 13, 2026 result: reject the index-only candidate

[Baseline run 38408063113](https://github.com/harshaattili-human/reconledger/actions/runs/38408063113)
at `d1cbc339532002fb0d7ef6e8fc093f90c142bafe` passed all 64 cases on each engine.
[Candidate run 38408443492](https://github.com/harshaattili-human/reconledger/actions/runs/38408443492)
at `347907436ff529a8ed4590b29b728ee56becc0c9` added only
`recon_batch_tenant_currency_sequence(tenant_id, currency, list_sequence)` and also
passed all 64 cases per engine. Both runs passed the packaged demo/body-bound/restart
and secured-JAR/key-replacement workflows. These are 64 distinct cases, not 128;
the new case contains 570 timed calls and 95 warmups per engine.

Both reports recorded 40,157 total table headers: 40,000 fixture rows plus 157 from
other cases. Environments were H2 2.3.232, PostgreSQL 16.15, pgJDBC 42.7.11,
Temurin Java 17.0.20.1, Linux amd64 on Ubuntu 24.04. Each database had its own runner.
Local Maven lacked the cached parent POM; Java/database execution was hosted.

### First-page currency filter

The separate PostgreSQL EXPLAIN for the large tenant used the **global sequence
index in both runs**, filtered 39,600 rows, and consumed 1,542 shared-buffer hits
to emit 101 rows (100 plus lookahead). It estimated 10,059 matching rows before the
index and 10,060 after, while the complete tenant/currency match count was 200.
The candidate was present but did not change this access path. This occurs with the
existing query-scoped unnamed-statement policy; avoiding generic-plan reuse alone
does not prevent this bad selection.

| First currency page | Baseline median / p95, ms | Index candidate median / p95, ms | Separate plan observation |
| --- | ---: | ---: | --- |
| PostgreSQL, large tenant | 3.158 / 3.429 | 3.180 / 3.374 | Same global sequence scan and 39,600 filtered rows |
| PostgreSQL, small tenant | 0.354 / 0.644 | 0.431 / 0.831 | Bitmap/sort; shared hits 12 to 11 |
| H2, large tenant | 0.063 / 0.098 | 0.064 / 0.080 | Currency scanCount 20,201 to tenant/currency scanCount 201 |
| H2, small tenant | 0.238 / 0.265 | 0.233 / 0.254 | Same scanCount change as large tenant |

Each timing cell summarizes 30 calls after five warmups. Keep the unfavorable
PostgreSQL small-tenant timing and essentially unchanged H2 large-tenant median:
these two separate runs do not isolate causality or establish a latency improvement.
H2's low repeated-call times despite large separate EXPLAIN scan counts also mean
those counters must not be assigned to every timed call. Cache effects and the
physical plan of each timed execution were not isolated.

### Remaining behavior and decision

The large tenant's **unfiltered** PostgreSQL first page also used the global sequence
index, removed 19,800 foreign rows and had 774 shared-buffer hits in both runs.
Its continuation page had seven hits and no removed rows reported. Currency
continuations and date/combined filters are retained in the full reports; first-page
behavior must not be generalized to every cursor or filter.

The index-only candidate did not address the target PostgreSQL scan, so its unreleased
V5 migration was removed before merge. No deployed database is being rolled back and
no released migration was edited. Adding index write/storage and migration cost is
not justified by this result; those costs were not measured either.

The large joint-selectivity overestimate suggests a statistics experiment next,
not another blind index addition. Compare multivariate most-common-value statistics
with and without the candidate index, plus alternate allocation orders and common
currencies. That is a hypothesis from this fixture, not a verified fix. PostgreSQL
[documents cross-column statistics](https://www.postgresql.org/docs/16/planner-stats.html#PLANNER-STATS-EXTENDED);
row-count estimates and the position of matches within sequence order are separate
concerns. Do not force a global plan setting or remove existing indexes based on this run.

Full reports retain ordered samples, all 19 plans per engine and environment details:
[baseline](../reports/tenant-browse-baseline-2026-10-13.json) and
[rejected candidate](../reports/tenant-browse-rejected-index-2026-10-13.json).

These are warm, sequential, in-process calls on one authored distribution, with
no concurrent writes, network HTTP timing, pool contention or production workload.
The measurements do not establish tenant metadata isolation or a service-level
objective. Different statistics, allocation orders and tenant sizes can change
plans. See PostgreSQL's [EXPLAIN guidance](https://www.postgresql.org/docs/16/using-explain.html)
and [multicolumn index rules](https://www.postgresql.org/docs/16/indexes-multicolumn.html).

Interview exercise: why can an index cover every filter and the requested order yet
still be ignored for `ORDER BY ... LIMIT`, and why is a fast continuation page not
evidence that the first page is cheap?

Follow-up: the [October 14 statistics experiment](tenant-statistics.md) compares
index availability and MCV statistics independently across two allocation orders.
It preserves this rejected-index baseline and does not change the runtime schema.
