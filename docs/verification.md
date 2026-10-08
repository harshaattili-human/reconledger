# Verification record

Latest verification on October 8, 2026: the same 42-test suite passes on H2 and
PostgreSQL. The packaged application's real-HTTP demo and saved-cursor restart checks
pass on both. This record describes a local prototype, not deployment, production
traffic or financial accuracy.

## Sparse batch filters — October 8, 2026

The follow-up [run 37785169114](https://github.com/harshaattili-human/reconledger/actions/runs/37785169114)
at `c73eb4433e8b7c3807c332e9ac7012df163f2cbc` also passed the same 42 cases per
engine and both packaged HTTP/restart checks. The existing characterization now
adds a PostgreSQL-only diagnostic for 20 executions in each of three planning modes.
It checks every page, records driver-created statement types and plan counters, and
restores the connection's original setting. Read the [observed generic-plan switch](prepared-plans.md).

[Run 37783840468](https://github.com/harshaattili-human/reconledger/actions/runs/37783840468)
passed at `0580d527154a6333ac24bc27d9831caa026dbe24` on H2 2.3.232 and
PostgreSQL 16.15, including all 42 cases and both HTTP/application-restart checks.
The existing characterization case now adds 20,000 headers and checks rare date,
currency, combined and no-match filters. The migration case upgrades retained V1
evidence through the latest migration, now V3.

The currency-only first-page plan previously scanned past 19,800 nonmatching rows.
V3 adds a currency/sequence index. [Before/after evidence](query-characterization.md)
records reduced plan scan work and the unfavorable PostgreSQL repository timing
alongside it. No general latency or throughput improvement is claimed. Compilation
and database execution were hosted; local Maven lacked the parent POM cache.

## Batch browsing and retained-data migration — October 7, 2026

[Run 37627505058](https://github.com/harshaattili-human/reconledger/actions/runs/37627505058)
passed at source `54a707aec5e4472899323e515b9b97bd72332e35` in
[PR #4](https://github.com/harshaattili-human/reconledger/pull/4). Both H2 2.3.232
and PostgreSQL 16.15 passed the same 42 cases: 9 engine, 25 existing API, 6 batch-list
API, 1 migration and 1 query-characterization case. There were no failures, errors or
skips. The Java 17/Ubuntu 24.04 jobs also passed both packaged HTTP demos and process
restart checks, including discovery through the filtered list and retained summaries.

The six new API cases cover 105 synthetic batches, default/maximum page bounds,
an exactly full last page, cursor replay, identical timestamps, combined filters,
no matches, header-only fields, idempotent replay, later inserts, rollback gaps and
invalid/overflowing parameters. A barrier holds a batch transaction after its sequence
has been allocated, lets a reader pass that position, then commits it. The test
demonstrates why a first-page refresh is required to discover that late commit.

The migration case creates V1 in a randomly named test schema, stores two synthetic
batch headers plus source/result/audit evidence, applies V2 and compares the retained
values. It checks unique positive sequences and that subsequent inserts advance past
the assigned values. The temporary schema is removed after the check. This verifies
the migration on populated fixtures, not online migration or backup recovery at scale.

Local whitespace, Python syntax and documentation-link checks passed. Maven's local
dependency cache was unavailable, so compilation and database execution were verified
through hosted CI. No new local database run or batch-list latency result is claimed.

## Bounded-read characterization — October 6, 2026

One integration test now creates a maximum-size 1,000-source-record batch and three
10,000-event result histories. It asserts the existing response and cursor contracts,
requires indexed audit access, records both query plans and writes observational warm
read timings to `target/query-characterization.json`. See
[the protocol and results](query-characterization.md).

[Run 37470641915](https://github.com/harshaattili-human/reconledger/actions/runs/37470641915)
passed 35 tests with no failures, errors or skips on each database at source
`8049376e00c4f19ee95231212a5c5539b0aa8d7f`. Jobs
[`112292904399`](https://github.com/harshaattili-human/reconledger/actions/runs/37470641915/job/112292904399)
and
[`112292904002`](https://github.com/harshaattili-human/reconledger/actions/runs/37470641915/job/112292904002)
also passed the H2 and PostgreSQL HTTP demo/restart checks. These are the same 35
cases on two engines, not 70 distinct tests.

The first PostgreSQL attempt failed only because the new test assumed the planner
would name the composite result/sequence index. Its actual ordered primary-key scan
returned the 201-row lookahead after filtering 402 interleaved rows. The assertion and
documentation now reflect both valid indexed plans; no application query changed.

## Audit pagination increment — October 4, 2026

Five additional API tests cover default/maximum page sizes, an exactly full final
page, cursor replay, equal timestamps, result isolation, sequence gaps, reviews added
between pages, a page read before a pending transaction commits, rollback after an
audit insert, and invalid/overflowing query parameters. The maximum-page fixture uses
205 sequential review transitions on one synthetic result. This is a functional
boundary check, not a latency or throughput benchmark.

The real-HTTP demo follows a one-event page cursor, and the smoke runner resumes
that saved cursor after restarting the application.

[Run 37207651998](https://github.com/harshaattili-human/reconledger/actions/runs/37207651998)
passed for source `4f24494b28afffd0c8f66a10d83f6141396cd3ff` in
[pull request #2](https://github.com/harshaattili-human/reconledger/pull/2).

| Database reported by JDBC | Engine tests | API tests | Failures / errors / skips | HTTP demo | Saved cursor after app restart |
| --- | --- | --- | --- | --- | --- |
| H2 2.3.232 | 9 passed | 25 passed | 0 / 0 / 0 | Passed | Passed |
| PostgreSQL 16.15 | 9 passed | 25 passed | 0 / 0 / 0 | Passed | Passed |

These are the same 34 cases on each engine. The runner is Ubuntu 24.04 with Temurin
Java 17; the workflow retains the Maven/Spring versions listed in the baseline below.
All 34 tests also passed locally with OpenJDK 17.0.20 after restoring the dependency
cache. The initial local packaged-app check encountered an invalid generated JAR;
rebuilding from an empty `target/` directory produced a working archive, and the
local H2 demo and cursor-resume restart check then passed. No source change was
needed for that recovery. Hosted verification used fresh build output.

## October 3 hosted baseline

[GitHub Actions run 37160388559](https://github.com/harshaattili-human/reconledger/actions/runs/37160388559)
passed for source commit `826c2fd2561e5b28baea3849601371f7d118d377` in
[pull request #1](https://github.com/harshaattili-human/reconledger/pull/1).

| Database reported by JDBC | Engine tests | API tests | Failures / errors / skips | HTTP demo | Application restart |
| --- | --- | --- | --- | --- | --- |
| H2 2.3.232 | 9 passed | 20 passed | 0 / 0 / 0 | Passed | Passed |
| PostgreSQL 16.15 | 9 passed | 20 passed | 0 / 0 / 0 | Passed | Passed |

These are 29 test cases executed on each engine, not 58 distinct cases. The workflow
uses Ubuntu 24.04, Temurin Java 17.0.20, Maven 3.10.0 through wrapper 3.3.4, Spring
Boot 3.5.16, JDBC and Flyway. PostgreSQL runs in a disposable `postgres:16` service;
the observed build was `16.15 (Debian 16.15-1.pgdg13+2)`. A test asserts the database
product name, so this result is not an H2 compatibility-mode run.

The same 29 tests and H2 restart check also passed locally on Linux with OpenJDK
17.0.20. Docker was unavailable in that workspace; PostgreSQL validation was done
in GitHub Actions, not claimed as a local run.

## Reproduce

```bash
./mvnw --batch-mode --no-transfer-progress verify
python3 scripts/smoke.py
```

This defaults to an isolated H2 test database and a temporary H2 file database for
the smoke check. A first Maven run needs network access. The local verification
used `-o` after populating the dependency cache; workspace-specific proxy settings
remain outside the repository.

For the PostgreSQL environment variables and disposable-database setup, follow
[the PostgreSQL guide](postgresql.md), then run the same Maven command and:

```bash
python3 scripts/smoke.py --database postgres
```

The smoke script runs `target/reconledger-0.1.0-SNAPSHOT-app.jar`, uses 11 synthetic
source records across six references, and checks a replay, a key conflict and two
review transitions. It stops and restarts the application against the same database,
then verifies the batch, evidence, review state, audit events and idempotency key.
It does not restart or crash the database server.

## What the tests cover

The engine tests cover decimal scale, negative reversals, all five outcomes,
ambiguous duplicates, canonical request fingerprints, repeated record IDs, empty
and overflow inputs, case sensitivity and extreme decimal exponents.

The API tests cover validation, exact decimal boundary values, all 1,000 supported
source records, six simultaneous same-key submissions, different payloads racing
for one key, retry after a creator rolls back, competing reviewers, stale transitions,
and rollback after injected result/audit write failures. A barrier holds initial key
lookups until every contender has observed a miss, forcing database conflict handling.
Injected database-error messages and expected duplicate-key errors in those tests
are not unresolved application failures.

## A failure that changed the implementation

The first PostgreSQL run, `37160187498`, passed 28 of 29 tests. H2 passed all 29.
`returnsReferencesAndEvidenceInCanonicalCaseSensitiveOrder` exposed a database
collation difference:

| Case | Reference order |
| --- | --- |
| Expected canonical order | `.REF`, `A-REF`, `A_REF`, `a-ref` |
| PostgreSQL result before the fix | `a-ref`, `A-REF`, `A_REF`, `.REF` |

The repository had relied on SQL `ORDER BY` for response ordering. The fix sorts the
bounded source and result lists with Java's case-sensitive string comparator, matching
the engine and normalization rules. The regression test also checks record-ID order
inside duplicate-reference evidence. The subsequent run above passes without
weakening that assertion.

## Earlier milestone and test setup

The first published source, `e9d1157747844f1db3254228db729a66028a128e`, passed 23 tests
and the H2 restart check in [run 37159327161](https://github.com/harshaattili-human/reconledger/actions/runs/37159327161).
The October 3 PostgreSQL increment added six cases and strengthened the retry test.

The local JVM did not support Mockito self-attachment. Tests use its subclass mock
maker for the non-final repository, preserving actual JDBC and transaction behavior.
The executable archive has an explicit `app` classifier, distinct from the plain JAR.

## Not yet verified

- Windows/macOS startup, application container deployment or public hosting.
- Load/throughput, database crash recovery, failover or replicas. The bounded-read
  characterization is sequential synthetic evidence, not a concurrent benchmark or
  service-level objective.
- Real financial records or real-world matching quality.
- Authentication, authorization, tenant isolation or tamper-resistant audit storage.

The tests and small synthetic demo verify specific behaviors. They are not a code
coverage percentage, a benchmark or proof of production readiness.
