# PostgreSQL logical restore rehearsal

An application restart reuses the original database. It does not establish that a
backup contains enough state to recover evidence, idempotency and review history.
`scripts/restore_smoke.py` checks those behaviors after restoring a custom-format
archive into a second, newly created PostgreSQL cluster.

## Run

Requirements: Java 17, Python 3, OpenSSL 3 and Docker with access to `postgres:16`.

```sh
./mvnw verify
python3 scripts/restore_smoke.py
```

The runner creates two uniquely named disposable containers, each bound to a random
loopback port, with no host data mounts. It accepts no existing database or container
target. The packaged application uses temporary synthetic signing keys and the
`postgres,secured` profiles. Existing database/trust/profile environment variables
are excluded. Cleanup removes only this invocation's containers and anonymous
volumes. The temporary archive and keys are removed afterward; the JSON success
receipt remains in `target/postgres-restore.json`. A failed rerun removes any stale
success receipt. CI retains the receipt artifact for 30 days, not the archive or keys.

## Protocol

1. Create two batches in each of two tenants using the same two idempotency keys.
   Each batch uses the eleven synthetic records in `examples/mixed-batch.json`.
   Review one mismatch per tenant through `IN_REVIEW` to `RESOLVED`.
2. Save one-row batch and audit pages. Stop the sole application writer. Advance
   both identity sequences by seven unused values, then capture every row of the
   four application tables and Flyway history, plus allocated sequence values.
3. Run `pg_dump -Fc` on the source. Restart the source app and commit one extra batch
   and one reopened review **after** the archive was taken, then stop it again.
4. Reject a half-truncated copy in a separate empty scratch database with
   `pg_restore --exit-on-error --single-transaction`; require a nonzero exit and no
   remaining public tables/sequences/views. Restore the intact archive into the
   empty target database using the same flags plus `--no-owner --no-acl`.
5. Compare all saved rows and sequence values exactly before starting the target
   app. Its startup validates restored Flyway history. Repeat same-key requests,
   payload conflicts, tenant isolation and saved batch/audit cursor reads over HTTP.
6. Reject a stale review and foreign-tenant writes. Append new reviews and a batch;
   require sequence allocation beyond the saved unused values. Verify the source's
   post-backup batch is absent and its key creates a new batch on the restored branch.

The checkpoint contains four batches, 44 source records, 24 results and four review
events. Full row comparison includes tenant ownership, normalized fingerprints,
original evidence, resolution notes, actor labels and timestamps. This is one
scripted workflow, not additional JUnit cases or a load test. The receipt records
server/client versions, image ID, JAR/archive hashes, row counts and sequence values.

## Recovery contract and limits

The test deliberately stops its writer before backup to define an exact checkpoint.
PostgreSQL supports consistent logical dumps during concurrent use, but this rehearsal
does not test concurrent backup or coordinate multiple writers. See the official
[pg_dump](https://www.postgresql.org/docs/16/app-pgdump.html) and
[pg_restore](https://www.postgresql.org/docs/16/app-pgrestore.html) references for
archive formats, error handling and transactional restoration.

Restoring an older backup loses later idempotency keys as well as later records.
Replaying a post-backup request can therefore create another batch. Reconcile the
upstream request ledger and freeze old writers before any real recovery cutover;
never treat this check as exactly-once delivery across data loss. The two branches
in this test are intentionally isolated and are never merged.

The dump covers the database, not cluster roles, grants, external issuer membership,
application configuration or signing-key recovery. Both clusters use the same
PostgreSQL image and a disposable superuser; this does not validate least-privilege
restore procedures, cross-version upgrades or production credentials. The synthetic
signer remains available during the rehearsal and is not recovered from the dump.

No database crash, physical backup, WAL archive, point-in-time recovery, failover,
replication, H2 backup, encryption-at-rest, off-host retention or recovery-time/loss
objective is established. Retention/deletion policy and an operational recovery
runbook remain work before real data or deployment. Archive hashes identify bytes;
they are not signed authenticity proofs. Restore only trusted archives.

Optional interview exercise: why can the API preserve idempotency across an app
restart yet create a second batch when a client retries after restoring an old backup?
Why must identity sequence state be restored beyond the highest stored row?

## Observed verification — October 15, 2026

Implementation `fdc2d438` passed [run 38741230215](https://github.com/harshaattili-human/reconledger/actions/runs/38741230215),
including the restore job `116443145101`, the same 64 Java cases on H2 and PostgreSQL,
and both existing default/secured packaged workflows. The restore check used
PostgreSQL server, pg_dump and pg_restore 16.15, Python 3.12.3 and Temurin Java
17.0.20.1 on Ubuntu 24.04. Its [receipt](../reports/postgres-restore-2026-10-15.json)
preserves the image/JAR/archive identities and checkpoint counts beyond artifact expiry.
The receipt's source commit is GitHub's test merge revision, not the PR head.

All 80 checkpoint rows (including four Flyway history rows) matched exactly. Both
identity sequences retained allocation value 11 despite their highest stored values
being 4; new batch/review writes advanced past the gap. The 15,624-byte archive
restored successfully; its half-truncated copy failed without partial public objects.
These byte counts identify this small fixture, not backup capacity or performance.
The post-backup batch and review were absent as expected, and retrying that batch's
lost key created a distinct batch on the restored branch.

Local Python syntax, documentation links and whitespace checks passed. Docker was
unavailable and the local Maven cache lacked its parent POM; Java/database execution
was hosted. No runtime application code or released migration changed.
