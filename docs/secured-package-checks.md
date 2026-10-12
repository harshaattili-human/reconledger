# Secured executable-JAR checks

The embedded HTTP tests verify the security rules inside a test application. They
do not establish that the executable archive starts with real profile/property
binding and a file-based public key. `scripts/secured_smoke.py` exercises that
packaging boundary using the same JAR produced by `./mvnw verify`.

## Run

Requirements: Java 17, Python 3 and OpenSSL 3 on Linux. After packaging:

```sh
python3 scripts/secured_smoke.py
# With the disposable RECON_DB_* variables from the PostgreSQL guide:
python3 scripts/secured_smoke.py --database postgres
```

H2 uses a temporary file database. PostgreSQL uses the explicitly supplied test
database and leaves its synthetic rows there; use a disposable database, never a
real-data database. Each invocation uses fresh tenant IDs and idempotency keys. The
script never deletes database rows. [PostgreSQL setup](postgresql.md) documents the
local disposable service. CI runs this check after the existing default-mode smoke
on each database.

The runner creates two temporary RSA-2048 key pairs, signs short-lived synthetic
RS256 JWTs through OpenSSL, and passes only a public PEM path to the application.
Private key files have mode 0600 inside the private temporary directory; tokens and
keys are neither printed nor committed. The issuer is an `.invalid` URL and no
identity provider is contacted. Requests bypass proxy configuration and go only to
an ephemeral loopback port. Profile/trust environment overrides are removed for
the child process; PostgreSQL test connection variables are retained.

## What the check requires

1. A blank configured audience makes the packaged secured profile exit with the
   expected trust-configuration error. A healthy launch follows, so an unrelated
   broken JAR cannot count as successful rejection.
2. With key A pinned, an absent bearer and a key-B token return 401; a read-only
   token cannot create a batch. Two tenants then use the same previously rejected
   key and receive different batches. Lists and known foreign IDs stay scoped.
3. Foreign and spoofed-actor reviews are rejected without an audit write. A valid
   review records the verified subject and version 1.
4. After stopping the application, replace the public PEM with key B and restart
   against the retained database. The old token is still unexpired but now returns
   401 for reads and reviews. Key-B tokens can replay each tenant's own batch,
   recover its source evidence and review state, and resume the saved audit cursor
   after a new version-2 review. Foreign result and audit reads still return 404.
5. Restart in the default demo profile against that same database. Private batch,
   result and audit IDs remain inaccessible; using the same key creates a separate
   demo batch.

A successful run writes `target/secured-smoke-h2.json` or
`target/secured-smoke-postgres.json` with the JAR SHA-256, Python/OpenSSL versions
and completed checks. CI prints it in the job log. An old receipt is removed before
a rerun; failure cannot preserve a stale success. Process shutdown, startup and
HTTP calls have timeouts, and only the runner's child process and temporary files
are cleaned up. No tokens, private keys or database credentials are in the receipt.

## Limits

This checks manual replacement of a pinned public key **with an application
restart**. It does not implement automatic rotation, overlapping keys, JWKS,
revocation, zero-downtime replacement or an external issuer's membership policy.
The issuer and subject namespace stay the same. A removed key's rejection is not
evidence that individual users can be revoked independently.

The database server is never restarted. Retained-state checks are not database
crash recovery, backup restoration, load or cross-tenant timing isolation evidence.
The service remains a local synthetic prototype. See [observed verification](verification.md)
for the exact run and environment rather than treating this procedure as a result.

OpenSSL references: [key generation](https://docs.openssl.org/3.0/man1/openssl-genpkey/)
and [digest signing](https://docs.openssl.org/3.0/man1/openssl-dgst/).

Interview exercise: why can an unexpired token stop working after this restart,
and why does that not prove user-level revocation or automatic key rotation?

## First execution failure

[Run 38275880816](https://github.com/harshaattili-human/reconledger/actions/runs/38275880816)
passed the Java suite and reached the new runner, but its first successful health
response exposed an invalid Python diagnostic accessor: `Request.method` is not
available when urllib infers GET/POST. The f-string evaluated even when the status
matched. Commit `5ca1328` uses `Request.get_method()`; the expected HTTP statuses
and security assertions are unchanged. That first run is not passing evidence for
the secured packaged workflow.


## Observed run

[Run 38276082310](https://github.com/harshaattili-human/reconledger/actions/runs/38276082310)
at `5ca1328` passed this complete workflow on H2 2.3.232 and PostgreSQL 16.15,
alongside the existing 63 Java cases and both default-mode packaged checks.
See [the verification record](verification.md#secured-executable-archive--october-12-2026)
for tool versions, JAR hash and local-versus-hosted scope.
