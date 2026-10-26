# Verification record

October 26: [JWKS trust mode](jwks-rotation.md) adds an alternative to the pinned
public key while retaining RS256, issuer, audience, expiry, subject, tenant and scope
checks. Implementation [run 40331965601](https://github.com/harshaattili-human/reconledger/actions/runs/40331965601)
passed all five jobs: the same 93 Java cases on H2 2.3.232 and PostgreSQL 16.15,
including the new real-HTTP unknown-key refresh; packaged default/secured checks,
manual pinned-key replacement and Boot 3 retained-data upgrades on both engines;
PostgreSQL restore; 12 demo, eight secured and ten repeated Chromium executions; and
the 73-library Java audit with zero advisory entries in that dated snapshot. This is
synthetic loopback rotation evidence, not external-provider, TLS/DNS, outage, cache
policy or user-revocation verification. The initial run's one existing startup-order
assertion failure and correction are preserved in the linked rotation record.

October 25: [privacy-bounded request diagnostics](request-diagnostics.md) add a
server-generated response ID and one route-template completion event without raw
paths, query strings, identities, headers or bodies. [Run 40196813968](https://github.com/harshaattili-human/reconledger/actions/runs/40196813968)
passed all five jobs: the same 91 Java cases on H2 and PostgreSQL, default/secured
packaged and retained-data upgrade checks on both engines, PostgreSQL restore, 30
existing Chromium executions and the 73-library Java audit. The default packaged
workflow matched 11 response IDs to redacted events on each database path. See the
linked record for the exact protocol, job IDs, environment and limits.

October 23: the secured review browser increment adds a fourth workflow for rejected
review saves. It checks that 403 retains a draft but blocks another submission until
reload, while 401 clears the workspace; neither rejection may change result version
or audit history. The earlier access-rejection workflow is repeated five times at
each of two widths. See [the protocol and preserved failure](secured-review-interface.md#explicit-rejection-forwarding--october-23-2026).
Final-head [run 40099384456](https://github.com/harshaattili-human/reconledger/actions/runs/40099384456)
passed all five jobs: 88 cases on each database, 12 demo browser executions, eight
secured executions and ten repetitions of the existing rejection workflow, plus
the packaged, retained-data upgrade, restore and audit checks.
Documentation-head run 40099686040 then exposed a reload-control race in three of
ten repetitions; the control now remains disabled until failed-save cleanup ends.
[Final run 40100058590](https://github.com/harshaattili-human/reconledger/actions/runs/40100058590)
passed all five jobs, including all ten repeated browser executions with one worker
and no retries.

October 22: the [per-tenant request budget](rate-limits.md#tenant-budget-verification--october-22-2026)
passed the same 88 Java cases on H2 and PostgreSQL, the default/secured packaged and
Boot 3 upgrade checks, logical restore, Java audit and 18 Chromium executions. Three
new unit/configuration cases cover bounded tenant state and concurrent bursts; the
existing three secured HTTP cases now exercise separate tenant bursts under one
process budget. This is correctness evidence with a controlled clock, not load or
fair-scheduling evidence. The linked record preserves the initial timing-fixture failure.

October 19: [per-tenant concurrency ceilings](concurrency-limits.md#tenant-ceiling-verification--october-19-2026)
passed the same 85 Java cases on H2 and PostgreSQL, all existing packaged/upgrade/
restore workflows and 18 Chromium executions. Four added cases verify occupancy,
identity and cleanup. Enabled-policy evidence is embedded HTTP, not packaged load
testing; the record links the exact implementation, CI jobs, environment and limits.

October 18: the [concurrent API cap](concurrency-limits.md) passed 81 cases on each
database, all packaged/upgrade/restore workflows and the existing 18 browser
executions. The enabled policy uses secured embedded-Tomcat tests; packaged checks
retain the disabled default. See the admission record for exact run/jobs and limits.

October 18: the [Boot 4 migration](boot-4-migration.md) passed the same 76 cases on
both databases, packaged security/restart/restore workflows and 18 browser
executions. The packaged Java audit now inventories 73 libraries with zero advisory
entries in its recorded snapshot. See the migration record for exact versions,
failed candidates and the separate retained-data upgrade protocol.

Latest implementation verification on October 17, 2026: the same 76-test suite
passes on H2 and PostgreSQL, plus packaged demo/body-bound/restart, secured JAR,
PostgreSQL restore and 18 existing Chromium executions. The new optional
[API request budget](rate-limits.md) adds five unit/configuration and three secured
HTTP cases. The enabled limiter is checked through embedded Tomcat; packaged/browser
workflows still run with it disabled. Full run IDs and the final-head PR are linked
in the rate-limit record. This is synthetic prototype evidence, not deployment,
production traffic or measured service capacity.

## Reviewer provenance — October 15, 2026

[Run 38805939498](https://github.com/harshaattili-human/reconledger/actions/runs/38805939498)
at `6c44a980e8f54723f40820e6a32a7efb9a56bae6` passed all four jobs: H2
`116666681139`, PostgreSQL `116666680727`, restore `116666681462` and browser
`116666681169`. Each database passed the same 67 cases with zero failures/errors/skips.
Three added cases cover the V4 retained-data migration and internal identity
resolution; existing real JWT/demo cases also assert the new API metadata and
reject client-supplied provenance. Package and restore checks preserve event metadata.

Six browser workflows at two widths passed (12 executions), including an injected
identity-label fixture and live demo labels. All six desktop/narrow evidence,
conflict and identity screenshots were inspected. Browser backing remains H2;
this does not add authenticated UI or assistive-technology verification. See the
[identity contract, migration and limits](reviewer-provenance.md).

Java 17.0.20.1, H2 2.3.232, PostgreSQL 16.15, Chromium 151.0.7922.34,
Playwright 1.62.1, Node.js 24.21.0 and Ubuntu 24.04. Local syntax, browser discovery,
documentation links and whitespace passed; local Maven lacked the parent POM,
so Java/database/browser execution was hosted.

## Local review interface — October 15, 2026

[Run 38760972219](https://github.com/harshaattili-human/reconledger/actions/runs/38760972219)
at `79342397` passed all four jobs: H2 `116511827247`, PostgreSQL `116511827221`,
restore `116511827024` and browser `116511827206`. The Java suite remains 64 cases
on each engine. Five browser workflows run at two widths (ten executions), against
the actual packaged JAR and an isolated H2 in-memory database.

Checks include keyboard review, unchanged source evidence, literal note rendering,
real competing-review conflicts, a dropped response after commit, pagination and
exact large-cursor decoding. Secured-profile Java HTTP assertions reject UI asset
access. The [interface record](review-interface.md) preserves the initial locator
failure, corrected test, screenshots, environment and limits. This is a local demo
UI, not authenticated browser integration or full accessibility verification.

## Logical backup restoration — October 15, 2026

[Run 38741230215](https://github.com/harshaattili-human/reconledger/actions/runs/38741230215)
at `fdc2d438` passed the same 64 cases on both engines and the existing packaged
checks. A separate job restored a quiesced synthetic backup into a fresh PostgreSQL
16.15 cluster, compared all table rows/sequence values, and exercised tenant access,
idempotency, cursors and new writes over secured HTTP. A truncated archive was rejected;
post-backup source commits were deliberately absent after restoration.
See [protocol, receipt and limits](backup-restore.md). This workflow adds no JUnit
cases and establishes neither crash recovery nor a recovery-time/loss objective.

## Tenant statistics comparison — October 14, 2026

[Run 38574849165](https://github.com/harshaattili-human/reconledger/actions/runs/38574849165)
passed 64 cases with zero failures, errors or skips on each H2 2.3.232 and PostgreSQL
16.15, plus all packaged default and secured workflows. Jobs were H2 `115889134209`
and PostgreSQL `115889134188`. The new PostgreSQL helper extends the existing
characterization case; H2 records not-applicable for extended-statistics measurements.

Two temporary 40,000-header allocations compare baseline, index, MCV and both:
48 first/continuation page observations, 960 timed calls and 240 warmups. Results
including lookahead are checked, and plans/catalog/timing/storage evidence is retained
in [the experiment record](tenant-statistics.md). Index + MCV changed the rare-page
scan in both orders; neither alone did, and the blocked common-currency scan remained.
No runtime SQL, migration or application-table statistics changed. These are temporary
table/JDBC measurements, not HTTP, repository latency, production or load evidence.

Environment: pgJDBC 42.7.11, Java 17.0.20.1, Linux amd64/Ubuntu 24.04. Local whitespace
and documentation/report checks passed; offline Maven lacked its parent POM, so
Java/database execution was hosted.

## Tenant-filtered query characterization — October 13, 2026

[Baseline run 38408063113](https://github.com/harshaattili-human/reconledger/actions/runs/38408063113)
and [index-candidate run 38408443492](https://github.com/harshaattili-human/reconledger/actions/runs/38408443492)
passed 64 cases with zero failures, errors or skips on each engine, plus all packaged
workflows. The candidate jobs were H2 `115334111972` and PostgreSQL `115334112161`.
One new case measures 19 tenant/filter/page combinations against 40,000 synthetic
header-only rows. Every repository response and cursor is checked; the 570 timed
calls per engine are observations inside that case, not new test cases or a load test.

The tenant/currency/sequence index candidate did not fix PostgreSQL's 39,600-row
filtering scan for the large tenant's first XTS page. It was removed before merge;
no runtime query, released migration or planning setting changes. The
[protocol, failed candidate, raw reports and remaining gaps](tenant-query-characterization.md)
retain both helpful and unfavorable measurements. H2 2.3.232, PostgreSQL 16.15,
pgJDBC 42.7.11 and Java 17.0.20.1/Linux amd64 were observed. Local whitespace and
link checks passed; Maven's uncached parent POM blocked local Java execution.

## Secured executable archive — October 12, 2026

[Run 38276082310](https://github.com/harshaattili-human/reconledger/actions/runs/38276082310)
at `5ca13287991ad5d72bb7645ca43b547eaf65f4ad` passed both jobs:
[H2 114901778974](https://github.com/harshaattili-human/reconledger/actions/runs/38276082310/job/114901778974)
and [PostgreSQL 114901778651](https://github.com/harshaattili-human/reconledger/actions/runs/38276082310/job/114901778651).
The Java suite remains 63 cases on each engine, with zero failures, errors or skips;
the scripted workflow is additional packaged-process evidence, not new JUnit cases
or 126 distinct tests.

Each job checked blank-audience startup rejection, bearer/scope enforcement, two
tenants reusing one key, foreign IDs, rejected actor spoofing and verified review
attribution. After replacing the pinned public PEM and restarting the executable
archive, the old unexpired token failed and new-key tokens recovered unchanged
source evidence, scoped idempotency and review/audit state. A second review resumed
the saved sequence cursor. A final switch to demo mode kept private IDs inaccessible
and created a separate demo batch with the same key. No runtime policy, schema or
identity-provider integration changed.

Observed environment: H2 2.3.232 and PostgreSQL 16.15, Temurin Java 17.0.20.1,
Spring Boot 3.5.16, Tomcat 10.1.55, Python 3.12.3, OpenSSL 3.0.13 and Ubuntu 24.04
on Linux amd64. Both receipts recorded JAR SHA-256
`2769e3f8e144244a9b9f83459761ca59d2905935a9eafc77744d60ff78e4f00c`.
This identifies the tested archive; it is not a reproducible-build guarantee.

Reproduce with `./mvnw verify`, then `python3 scripts/secured_smoke.py` and the
[PostgreSQL variant](secured-package-checks.md#run). Local Python syntax,
OpenSSL signing/verification, documentation links and whitespace checks passed.
Local Maven lacked the cached parent POM; Java/database execution was hosted.

The [first run and runner fix](secured-package-checks.md#first-execution-failure)
remain documented. This is manual pinned-key replacement with a process restart,
not automatic rotation, individual-token revocation, external membership
verification, database restart/recovery or production deployment.

## Tenant-scoped access — October 11, 2026

[Run 38147018174](https://github.com/harshaattili-human/reconledger/actions/runs/38147018174)
at `588c019b64e8f31877b229dc51586075b8c49fca` passed 63 tests with no
failures, errors or skips on each H2 2.3.232 and PostgreSQL 16.15. Both packaged
HTTP demos, body-bound checks and application-process restarts also passed. These
are the same 63 cases on both engines, not 126 distinct cases.

Five added cases exercise invalid/missing tenant claims and forged tenant inputs;
foreign detail/audit/review IDs and interleaved filtered pages; six simultaneous
requests using one key across two tenants; missing secured repository context; and
unauthenticated demo access to private-tenant rows. The concurrent case observed
exactly one new batch per tenant, with same-tenant replays and changed-payload
conflicts. It is a correctness case, not a concurrency/load benchmark. The existing
migration case now verifies retained V1 evidence through V4, default demo ownership,
cross-tenant key reuse and same-tenant uniqueness. It does not simulate a migration
crash or establish online migration safety.

H2 job [114492406844](https://github.com/harshaattili-human/reconledger/actions/runs/38147018174/job/114492406844)
and PostgreSQL job [114492406702](https://github.com/harshaattili-human/reconledger/actions/runs/38147018174/job/114492406702)
used Java 17.0.20.1, Spring Boot 3.5.16, Tomcat 10.1.55 and Ubuntu 24.04.
Run `./mvnw verify` with the default test configuration and then the
[PostgreSQL configuration](postgresql.md). Local whitespace/documentation links
passed; Java/database execution was hosted because local Maven dependency resolution
was unavailable in this session.

See [tenant ownership](tenants.md) for the breaking token contract, migration and
issuer-membership assumptions. These synthetic checks establish the tested API
boundary, not external provider membership configuration, database row-level security,
metadata/timing isolation or production readiness. Earlier prepared-plan reports
characterize unscoped queries; they do not prove latency for the new tenant predicate.

## Authenticated reviewer profile — October 11, 2026

[Run 38145745547](https://github.com/harshaattili-human/reconledger/actions/runs/38145745547)
at `2ff0def93bae283d3a6086a9006b96152d68635c` passed 58 tests with no
failures, errors or skips on each database. The eight added cases are six secured
HTTP cases, one decoder-configuration case and one default-demo actor HTTP case.
The same 58 cases ran on H2 2.3.232 and PostgreSQL 16.15; this is not 116 distinct
cases. Both packaged-app demos, body-bound checks and process restarts also passed
in their existing unauthenticated mode.

The secured cases send actual HTTP/1.1 requests through embedded Tomcat with fresh
in-memory RSA signing keys and the application's real JWT decoder. They check
signature/algorithm, issuer, audience, expiry, not-before and bounded subjects;
all data routes and independent scopes; actor spoofing; unchanged keys/state/audit
after rejection; successful subject attribution; optimistic conflict; body bounds;
and header-only, stateless authentication. No identity provider or signing secret
is contacted or committed. The [authentication guide](authentication.md) gives
configuration, commands and the single-workspace boundary.

Observed environment: Temurin Java 17.0.20.1, Spring Boot 3.5.16, embedded Tomcat
10.1.55 and Ubuntu 24.04/Linux amd64. H2 job
[114488595120](https://github.com/harshaattili-human/reconledger/actions/runs/38145745547/job/114488595120)
and PostgreSQL job
[114488595358](https://github.com/harshaattili-human/reconledger/actions/runs/38145745547/job/114488595358)
contain the results. Reproduce with `./mvnw verify` and the
[PostgreSQL test configuration](postgresql.md).

Local whitespace and documentation-link checks passed. Local Maven could not
resolve the uncached parent POM because Maven Central DNS was unavailable;
compilation and database execution evidence came from hosted CI. This verifies
synthetic application behavior, not external issuer integration, key rotation,
tenant isolation, TLS deployment or tamper-resistant audit storage. Historical
demo actors remain self-reported labels.

## JSON body bounds — October 10, 2026

[Run 38060766000](https://github.com/harshaattili-human/reconledger/actions/runs/38060766000)
at `78855c7ba9e346864166a6150c72a4ad97db635c` passed 50 tests with no
failures, errors or skips on each of H2 2.3.232 and PostgreSQL 16.15. These are the
same cases on two engines. Five new cases send HTTP/1.1 requests to embedded Tomcat;
two test early rejection and bounded stream consumption. Both packaged smoke jobs
also passed fixed-length/chunked overflow rejection, a retry using the unreserved
key, the ordinary demo and application-process restart.

The [request-bounds record](request-bounds.md) explains exact-limit acceptance,
one-byte overflow, review-state/audit preservation, UTF-8 bytes, compression rejection
and the maximum 1,000-record input. The limit applies before JSON parsing; it does
not establish aggregate memory bounds, rate control or slow-client protection.
Local whitespace, documentation links and Python syntax passed. Maven's local cache
still lacked the parent POM, so compilation and database execution were hosted.

## Query-scoped plan policy — October 10, 2026

[Run 38055419493](https://github.com/harshaattili-human/reconledger/actions/runs/38055419493)
at `6f912d95dd997002c64b0bdfb37e29ef3ee506d7` passed 43 cases on each
database and both packaged HTTP/application-restart checks. The extra unit case
covers the pgJDBC wrapper and portable statement paths. The PostgreSQL-only probe
ran 252 correctness-checked calls across automatic, forced-custom and query-scoped
modes. All 84 query-scoped calls remained unnamed and the final rare query used the
currency index for each primer. See the [protocol and measured tradeoff](mixed-prepared-plans.md).

Application SQL values remain bound, and no session/server planning setting changed.
The PostgreSQL dependency is now available at compile time because the application
uses its statement-level extension; packaging already included the driver. Local
Maven still lacked its parent POM cache, so compilation and database execution were
hosted. This is sequential synthetic evidence, not a concurrent throughput result.

## Mixed prepared requests — October 9, 2026

[Run 37935894837](https://github.com/harshaattili-human/reconledger/actions/runs/37935894837)
at `d598b062dc38257b80881e7a4fd80a7dea39b4b7` passed all 42 tests on each
engine and both packaged HTTP/application-restart checks. The PostgreSQL-only
probe extends the existing characterization with six statement histories and 168
correctness-checked calls. Per-call counters show how different initial requests
changed plan selection for an identical mixed tail. See the
[protocol, results and limits](mixed-prepared-plans.md). No runtime setting changed;
this is sequential synthetic evidence, not a concurrent load test.

## Sparse batch filters — October 8, 2026

[Run 37869485412](https://github.com/harshaattili-human/reconledger/actions/runs/37869485412)
at `16d7ba574324bfb180f591a473de5fc07223b14f` passed 42 cases on each database
and both packaged HTTP/restart checks. The PostgreSQL diagnostic now covers two
currencies, three page sizes and three planning modes, with 20 correctness-checked
executions per combination. The [matrix results](prepared-plans.md) distinguish
small-page custom plans from the large rare-page generic scan. Runtime settings
remain unchanged; mixed statement histories and concurrent writes are not measured.

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
- External identity-provider membership configuration, automatic key rotation, tenant lifecycle
  administration or tamper-resistant audit storage. JWT and tenant API checks are
  covered above; database-level, timing and allocation-metadata isolation are not.

The tests and small synthetic demo verify specific behaviors. They are not a code
coverage percentage, a benchmark or proof of production readiness.
