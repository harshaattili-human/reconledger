# ReconLedger

Compare two sets of transaction records, explain discrepancies, and record review
decisions without changing the original evidence.

ReconLedger is a Java 17 / Spring Boot 3 backend project. It focuses on exact decimal
comparison, durable idempotency, concurrent updates, and database rollback. Matching
is deterministic: duplicate references remain exceptions even when their totals agree.

Independent portfolio project by Harsha Attili, developed with AI coding assistance.
All examples are synthetic; no employer code, production records, or customer data
are included. The service does not move money or execute financial transactions.

## Run locally

Requirements: Java 17 or later, network access for the first Maven download, and
Python 3 only if you want to run the scripted demo. Maven is supplied by the wrapper.

```bash
git clone https://github.com/harshaattili-human/reconledger.git
cd reconledger
./mvnw verify
java -jar target/reconledger-0.1.0-SNAPSHOT-app.jar
```

The API listens on `http://127.0.0.1:8081`. H2 stores data under `.local/` relative
to the working directory; restarting from the same directory retains batches and
audit events. Stop the server with Ctrl+C. On Windows, use `mvnw.cmd verify`.
Use the `-app.jar` artifact to run the service; the plain JAR contains application
classes without the runtime dependencies.

In another terminal:

```bash
python3 scripts/demo.py
```

The demo submits 11 synthetic records across six references. It checks two matches,
one amount mismatch, one missing record on each side, and one duplicate-reference
exception. It also checks a safe retry, a conflicting retry, and two review events.
Each invocation uses a fresh idempotency key and creates a new demonstration batch.

Two cases from [the demo input](examples/mixed-batch.json) illustrate the matching rules:

| Reference | Left amounts | Right amounts | Result |
| --- | --- | --- | --- |
| `INV-101` | 80.00 | 85.00 | `AMOUNT_MISMATCH` |
| `INV-104` | 20.00, 20.00 | 40.00 | `DUPLICATE_REFERENCE` |

The second case stays open for review even though the totals agree. The service
cannot tell whether those two left records represent a valid split or a duplicate import.

For an automated check that starts and stops its own server in a temporary directory,
run `python3 scripts/smoke.py` after packaging. It also restarts the process and checks
that the batch, review history and idempotency key survive.

## Implementation and checks

| Concern | Implementation | Evidence |
| --- | --- | --- |
| Exact amounts and ambiguous duplicates | `BatchNormalizer`, `ReconciliationEngine` | Decimal, reversal, duplicate, ordering and validation tests |
| Retried requests | SHA-256 of normalized input plus database unique key | Sequential retries, changed payload and six simultaneous submissions |
| Competing reviewers | Version-checked SQL update | Two reviewers: one succeeds, one receives HTTP 409 |
| Partial database failure | One transaction per batch or review transition | Injected result/audit write failures leave prior state intact |
| Traceability | Immutable source records, result IDs and ordered review events | API lifecycle test and runnable demo |

Start with [the design decisions](docs/design.md), then inspect
[the integration tests](src/test/java/dev/harshaattili/reconledger/ReconciliationApiTest.java).
[Verification evidence](docs/verification.md) distinguishes checks actually run from
planned validation. [API examples](docs/api.md) explain the request and error contract.
The [query characterization](docs/query-characterization.md) explains the larger
synthetic fixtures, recorded plans and limits of the timing observations.

## Supported behavior

- One business date and currency label per batch; at most 500 records on each side.
- JSON request bodies capped at 512 KiB before parsing, including chunked requests.
- Case-sensitive reference matching with five explicit outcomes.
- Amounts exactly representable to two decimal places, including negative reversals.
- `Idempotency-Key` replay across process restarts while the database is retained.
- Review transitions with mandatory notes, optimistic concurrency and paginated audit history.
- Paginated batch summaries with exact business-date and currency filters.
- Flyway schema migrations, JDBC persistence and a local health endpoint.

Resolving an exception records a human-entered review decision. It does **not**
turn a mismatch into a match, correct the source data, or certify an amount.

## Boundaries

This is a local prototype, not a deployed financial system. The default server binds
to loopback. There is no authentication or authorization: `actor` is self-reported,
and audit rows are not tamper-proof against a database administrator. Do not expose
it to a public network or put real financial/customer records into it.

Currency is an uppercase three-letter label, not an ISO currency validator. Every
amount uses two decimal places; currency-specific minor units, FX, fuzzy matching,
settlement rules, multiple tenants, retention policies, and large-file ingestion
are outside this milestone. Request bodies are bounded, but rate limits and access
control remain deployment prerequisites. Result counts describe references, not
source-record counts or an accuracy score.

H2 is the default local database. CI runs the same contract suite and packaged-app
restart checks on H2 and PostgreSQL 16. [The PostgreSQL guide](docs/postgresql.md)
shows how to repeat the checks with a disposable database; [verification evidence](docs/verification.md)
records the exact versions, successful run and a collation bug found along the way.
See [next work](docs/roadmap.md).

Audit reads return at most 200 events per page (50 by default). The response is now
`{events, nextAfterSequence}` instead of the initial prototype's bare array. See
[pagination and client migration](docs/api.md#read-audit-history).
