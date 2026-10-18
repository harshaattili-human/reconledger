# Packaged Java dependency audit

The audit scans the executable Spring Boot JAR, including the libraries actually
shipped under `BOOT-INF/lib`. A POM-only scan can differ from the packaged runtime;
the plain application JAR does not contain those dependencies.

After packaging, reproduce with Docker and Python 3:

```sh
python3 scripts/test_jar_audit.py
bash scripts/audit_jar.sh target/reconledger-0.1.0-SNAPSHOT-app.jar target/java-audit
```

Use a new output directory each time. The script refuses an existing directory so
a failed invocation cannot reuse a previous success receipt. It copies the JAR into
a temporary read-only scanner input and records that copy's SHA-256 plus hashes of
every packaged library. It does not execute the JAR.

Trivy 0.75.0 runs as the caller's UID/GID with dropped capabilities and no Docker
socket. Only its input, cache and output directories are mounted. The version-tagged
scanner's resolved image digest and scanner/database metadata are retained; the
digest is observed, not pinned. Network access is required for advisory and Java
identification databases. See [Trivy's Java detection contract](https://trivy.dev/docs/v0.75/guide/coverage/language/java/).

One unfiltered JSON scan supplies both evidence and the gate. The inventory checker
requires Trivy schema 2 and a named, versioned Java package with a matching archive
path for every `BOOT-INF/lib/*.jar` entry. Empty scans, missing libraries, plain JARs
and scanner errors fail. Matching inventory paths establish detection coverage, not
that the scanner inferred every package coordinate correctly.

The gate rejects HIGH/CRITICAL advisory entries with a known fixed version. Unfixed
high-severity findings and lower-severity findings remain in the report; passing
does not mean vulnerability-free. No ignore list or reachability exception is used.
CI retains full findings, inventory/hashes and scanner metadata for 30 days.

The four Python fixture tests exercise coverage rejection and the gate policy; they
do not run Trivy. The hosted scan is separate evidence. This audit excludes the
JVM/host OS, Maven/plugins/test dependencies absent from the executable JAR, and
first-party application or embedded loader bytecode. It does not establish
exploitability, provenance authenticity or production readiness. Re-run as advisory
databases change; a JAR hash identifies an artifact, not a reproducible-build claim.
