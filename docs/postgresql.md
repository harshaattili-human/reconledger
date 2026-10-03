# PostgreSQL verification

The `verify-postgres` GitHub Actions job starts a disposable PostgreSQL 16 service,
applies Flyway migrations and runs the same API tests as the H2 job. The test suite
asserts the database product name so a PostgreSQL job cannot silently pass on H2.
The server's exact version is printed in the test log. See [verification evidence](verification.md)
for results actually observed; configuring a job is not itself a passing result.

The PostgreSQL image is pinned to major version 16 and receives its patch updates.
Both jobs pin the runner OS to Ubuntu 24.04. Test credentials in the workflow belong
only to its disposable service; they are not production secrets.

## Run the checks with a local disposable database

With Docker installed, start a database bound to loopback:

```bash
docker run --rm --name reconledger-test-db \
  -e POSTGRES_DB=reconledger_test \
  -e POSTGRES_USER=recon_test \
  -e POSTGRES_PASSWORD=disposable-local-password \
  -p 127.0.0.1:5432:5432 -d postgres:16
docker exec reconledger-test-db pg_isready -U recon_test -d reconledger_test
```

Wait for `pg_isready` to report that it is accepting connections. In the repository:

```bash
export RECON_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/reconledger_test
export RECON_TEST_DB_USER=recon_test
export RECON_TEST_DB_PASSWORD=disposable-local-password
export RECON_TEST_DB_VENDOR=PostgreSQL
./mvnw verify

export RECON_DB_URL="$RECON_TEST_DB_URL"
export RECON_DB_USER="$RECON_TEST_DB_USER"
export RECON_DB_PASSWORD="$RECON_TEST_DB_PASSWORD"
python3 scripts/smoke.py --database postgres
```

Use a disposable database. The tests apply migrations and insert synthetic batches
and review events; they do not clean an existing database or isolate other writers.
The smoke check starts the packaged application with its actual `postgres` profile,
stops it, restarts it against the same database, and verifies recovered evidence,
review state, audit history and idempotency. It restarts the **application process**,
not the database server, and does not test crash recovery or failover.

When finished, `docker stop reconledger-test-db` removes this named temporary
container and its test data because it was started with `--rm`.

To return to the default H2 test run, unset the four `RECON_TEST_DB_*` variables.
The default smoke command uses an isolated temporary H2 file database even if other
database environment variables are set.

## What the concurrency checks establish

The retry tests hold every contender after its initial key lookup has returned empty.
Only then can the inserts proceed. This forces the unique-key recovery path instead
of relying on thread scheduling to create a race. Cases include identical requests,
different payloads sharing a key, and a first creator that rolls back while another
request is waiting to insert.

These checks exercise one database instance and the configured connection pool.
They do not establish distributed exactly-once delivery, throughput, replica behavior
or resilience to database/network outages.
