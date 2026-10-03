# Verification record

Verified on October 3, 2026: the same 29-test suite passes on H2 and PostgreSQL.
The packaged application's real-HTTP demo and process-restart checks pass on both.
This record describes a local prototype, not deployment, production traffic or
financial accuracy.

## Current hosted result

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
The current suite adds six cases and strengthens the existing retry test.

The local JVM did not support Mockito self-attachment. Tests use its subclass mock
maker for the non-final repository, preserving actual JDBC and transaction behavior.
The executable archive has an explicit `app` classifier, distinct from the plain JAR.

## Not yet verified

- Windows/macOS startup, application container deployment or public hosting.
- Load/throughput, long audit histories, database crash recovery, failover or replicas.
- Real financial records or real-world matching quality.
- Authentication, authorization, tenant isolation or tamper-resistant audit storage.

The tests and small synthetic demo verify specific behaviors. They are not a code
coverage percentage, a benchmark or proof of production readiness.
