# Packaged HTTPS JWKS checks

`scripts/jwks_smoke.py` runs the actual executable JAR against a disposable Python
HTTPS server. It creates two local certificate authorities, three server
certificates and two separate RSA signing keys. The JAR receives an explicit PKCS12
JVM truststore containing only the trusted test CA. No trust-all manager, disabled
hostname verification or application TLS override is used.

The same database survives three JAR launches: an untrusted certificate chain, a
trusted chain with the wrong subject alternative name, and a trusted matching IP
certificate. The first two authenticated writes must return sanitized 503/no-store,
without an authentication challenge or retry delay, and must never reach the JWKS
HTTP handler. Under the valid certificate, those same tenant/idempotency keys must
create new batches (201), proving the rejected writes did not reserve them.

The valid HTTPS phase also checks scope rejection, verified review issuer
provenance, replacement of key A with B without restarting the JAR, unknown-kid
retrieval, rejection of an unexpired removed-key review without audit changes, and
idempotent replay using B. Key removal is only observed after an unknown kid causes
a refresh; this does not establish immediate revocation of cached keys.

Reproduce with Java 17, OpenSSL 3, Python 3 and `keytool`:

```sh
./mvnw verify
python3 scripts/jwks_smoke.py
# Use only a disposable database configured as in docs/postgresql.md:
python3 scripts/jwks_smoke.py --database postgres
```

CI runs both paths and retains `target/jwks-smoke-{h2,postgres}.json` for 30 days.
The receipt records the tested JAR hash, runtime versions, observed HTTP fetch
counts and declared checks. Temporary private keys, truststore, application logs
and H2 database are removed; no bearer tokens or key material are artifacts.
PostgreSQL retains this script's uniquely named synthetic tenant rows in the
supplied disposable database. A failed rerun removes the previous success receipt.

Local certificate generation and Python TLS preflight passed on October 27, 2026:
the matching certificate reached HTTP once; wrong-host and untrusted certificates
failed certificate verification before HTTP. Packaged Java/database execution is
recorded below after hosted verification. This preflight is not Java evidence.

This is a local synthetic issuer fixture. External provider membership, OIDC
login/discovery, public DNS, provider cache-expiry policy, user revocation,
certificate rotation and deployed TLS remain unverified. Application API traffic
in this rehearsal is loopback HTTP. The test neither deploys nor authenticates an
external identity provider.

References: [Python TLS contexts](https://docs.python.org/3.12/library/ssl.html)
and [Java 17 JSSE truststore configuration](https://docs.oracle.com/en/java/javase/17/security/java-secure-socket-extension-jsse-reference-guide.html).

## Hosted evidence — October 27, 2026

Implementation `2dc0565076366adeca9cbcc46f9f2370556e5f0d`,
[run 40579128829](https://github.com/harshaattili-human/reconledger/actions/runs/40579128829),
passed all five jobs: H2 122453475197, PostgreSQL 122453475374, restore
122453474876, browser 122453475428 and audit 122453475404. Both new packaged HTTPS
paths passed. Each observed zero HTTP requests under the untrusted/wrong-host
certificate and three under the valid certificate (initial retrieval, B refresh,
then A's removed-key lookup). The exact receipts are preserved in
[the dated report](../reports/jwks-https-2026-10-27.json).

The existing 114 Java cases passed with zero failures/errors/skips on each engine
(H2 2.3.232 and PostgreSQL 16.15); these are the same cases, not 228 distinct cases.
The new Python/JAR workflows are additional process checks, not added JUnit cases.
Default and secured package/restart, pinned-key replacement, Boot 3 retained-data
upgrade, PostgreSQL restore and 30 Chromium executions also passed. The audit
inventoried 73 packaged libraries with zero advisory entries in this dated snapshot;
that is not a vulnerability-free claim. Hosted Java 17.0.20.1, Python 3.12.3,
OpenSSL 3.0.13 and Ubuntu 24.04 were used. No runtime policy or migration changed.

Interview exercise: why can a valid JWT signature still be unsafe if the client
accepts a JWKS certificate for the wrong host, and why does removing a key from the
server not immediately remove it from a client's cache?
