# Tenant-filtered batch browsing

The tenant boundary changed every batch-list query. Earlier
[prepared-plan experiments](mixed-prepared-plans.md) used unscoped SQL, so their
plans do not establish how the current tenant predicate behaves.
`TenantBrowseCharacterizationTest` measures the current repository path separately.
It changes no application query, index or connection setting.

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

## Interpretation

Results are pending the first hosted run. A bounded result does not imply bounded
scan work. The fixture intentionally puts many foreign XTS rows after the small
and large tenants' XTS rows. A global currency index and a tenant/sequence index
can therefore face different filtering work. An index change needs measured
support, including its extra storage and write cost.

These are warm, sequential, in-process calls on one authored distribution, with
no concurrent writes, network HTTP timing, pool contention or production workload.
The measurements do not establish tenant metadata isolation or a service-level
objective. Different statistics, allocation orders and tenant sizes can change
plans. See PostgreSQL's [EXPLAIN guidance](https://www.postgresql.org/docs/16/using-explain.html)
and [multicolumn index rules](https://www.postgresql.org/docs/16/indexes-multicolumn.html).
