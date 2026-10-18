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

## Initial findings, October 18, 2026

[Run 39103255589](https://github.com/harshaattili-human/reconledger/actions/runs/39103255589)
at `279f005` identified all 57 packaged libraries and reported 18 advisory entries,
including 11 fixable HIGH/CRITICAL entries. The gate failed. The
[baseline report](../reports/java-audit-baseline-2026-10-18.json) retains all findings,
library hashes and database metadata. These are scanner matches, not demonstrated exploits.

The preceding run used `filesystem`, which skipped JARs entirely. The inventory
checker rejected the empty scan. The corrected command uses `rootfs`, as required
by [Trivy's target coverage matrix](https://trivy.dev/docs/v0.75/guide/coverage/language/).

Candidate upgrades use Jackson BOM 2.21.7, Log4j 2.25.5, Tomcat 10.1.60 and pgJDBC
42.7.14. The BOM keeps Jackson modules aligned. Tomcat 10.1.58 was not released;
10.1.60 also incorporates subsequent fixes. pgJDBC 42.7.14 includes a newer
authentication-policy fix beyond the scanner's minimum 42.7.12. Sources:
[Jackson](https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.21.7),
[Log4j](https://logging.apache.org/log4j/2.x/release-notes.html),
[Tomcat](https://tomcat.apache.org/security-10.html),
[pgJDBC](https://jdbc.postgresql.org/changelogs/2026-10-07-42.7.14-release/).

Spring MVC findings CVE-2026-47884 and CVE-2026-47890 remain a migration decision.
The scanner classifies both CRITICAL; Spring's advisories classify them MEDIUM and
LOW, respectively, and describe XSLT view rendering and SSE view-fragment conditions.
Spring lists 6.2.20 as enterprise-only and 7.0.9 as the OSS fix. No exception is
applied to the gate and no exploitability conclusion is inferred from these labels.
A compatible framework/Boot migration needs its own verification; mixing Spring 7
into Boot 3 is not adopted here. See the
[XSLT advisory](https://spring.io/security/cve-2026-47884/) and
[SSE advisory](https://spring.io/security/cve-2026-47890/).

## Updated candidate verification

[Run 39103499816](https://github.com/harshaattili-human/reconledger/actions/runs/39103499816)
at `b82b453` identified all 58 libraries after the updates. Findings fell from 18
entries to the two Spring MVC entries above; the audit remains failed and this
candidate is not ready to merge. The [updated report](../reports/java-audit-updated-2026-10-18.json)
retains those findings. Both scans used the vulnerability database updated
2026-10-18T00:43:37Z and Java identification database updated 2026-10-18T00:58:00Z.

The same 76 Java cases passed on H2 2.3.232 and PostgreSQL 16.15, with no failures,
errors or skips. Packaged demo/body-bound/restart, secured/key-replacement,
PostgreSQL restore and 12 demo plus six secured Chromium executions also passed.
Environment: Ubuntu 24.04.5, Java 17.0.20.1, Boot 3.5.16, Tomcat 10.1.60,
pgJDBC 42.7.14. These checks establish compatibility for the covered synthetic
workflows, not exploitability or deployment safety. Java and scanner execution was
hosted; local checks covered the four Python fixtures, shell syntax and diff/link review.

Next: evaluate a supported Boot/Framework upgrade with the full security, tenant,
migration and packaged-workflow suite. Do not suppress these advisories merely to
merge the audit. Interview exercise: why can functional tests pass while a packaged
dependency audit correctly blocks the same change?
