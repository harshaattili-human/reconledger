# Boot 4 compatibility migration

The Java audit on Boot 3.5.16 still reported two fixable Spring MVC entries.
This candidate moves to Boot 4.0.8, whose managed Framework version is 7.0.9,
without suppressing either finding. CI must establish compatibility and rescan
the packaged JAR before this candidate can merge.

MVC and Flyway use Boot 4's dedicated starters. The MockMvc annotation follows
its new module package. Java remains 17. H2 stays at 2.3.232 to avoid combining
the framework change with a file-database upgrade. Released migrations are
unchanged.

The first candidate used the deprecated Jackson 2 bridge. All 76 Java cases
passed on H2, but packaged checks rejected its health-response shape. The same
failure persisted after moving to Jackson 3, so it was not a mapper-only issue.
The diagnostic run observed `{"groups":["liveness","readiness"],"status":"UP"}`.
Boot 4 enables liveness/readiness groups by default. This application explicitly
disables those probes to retain its existing public health contract. The secured
HTTP test now checks the exact payload, as the packaged tests already did.
The revised candidate uses Boot's default Jackson 3 mapper and
updates test tree imports and property enumeration. Unknown request properties
remain rejected. Jackson 3.1.7 and Tomcat 11.0.26 replace vulnerable managed
versions identified by the artifact audit. No health assertion is weakened.
Jackson 2 remains a transitive library and is separately managed with Boot 4's
`jackson-2-bom.version=2.21.7`; HTTP and the test mapper use Jackson 3.

Required checks are both database suites, default and secured packaged HTTP
workflows, process restarts, key replacement, PostgreSQL restore, browser
workflows and the unchanged executable-JAR audit. Local Maven could not resolve
the uncached Boot 4 parent offline; no local compilation result is claimed.

## Artifact and compatibility evidence

[Run 39108083386](https://github.com/harshaattili-human/reconledger/actions/runs/39108083386)
at `5e65383` passed all five jobs: 76 cases on each of H2 2.3.232 and PostgreSQL
16.15, packaged default/secured workflows and restarts, PostgreSQL restore,
12 demo plus six secured Chromium executions, and the Java audit. The same 76
cases run on each database; they are not 152 distinct tests. The scanner identified
all 73 packaged libraries and reported zero advisory entries in that database
snapshot. This does not establish absence of vulnerabilities.

The [retained report](../reports/boot-4-audit-2026-10-18.json) preserves hashes,
scanner metadata and every finding from three migration candidates: 77 libraries
with 17 entries, then 73 with seven, then 73 with zero. The original Boot 3
baseline and intermediate two-finding result remain in [the audit record](java-audit.md).
No advisory was suppressed. Java 17.0.20.1, Boot 4.0.8, Framework 7.0.9,
Tomcat 11.0.26 and Jackson 3.1.7 were checked in hosted CI.

## Retained-data upgrade rehearsal

`scripts/secured_smoke.py --seed-jar PATH` creates its synthetic tenant data with
the supplied trusted previous JAR, stops it, and opens the same database with the
current JAR. It then verifies idempotency, source evidence, review state and exact
audit history, replaces the pinned public key, appends a review and resumes the
cursor. The existing demo-isolation check follows. Separate upgrade receipts
record both JAR hashes. CI builds the fixed Boot 3 checkpoint `844c117` and runs
this path on H2 and PostgreSQL. Do not supply untrusted JARs.

[Run 39108160588](https://github.com/harshaattili-human/reconledger/actions/runs/39108160588)
at `a9f269b` passed both upgrade rehearsals and all five existing jobs. H2 job
`117618390546` and PostgreSQL job `117618390493` each printed a receipt with
`cross_version_restart: true`; these are two packaged workflows, not new JUnit
cases. The source and destination use the same database engine/version.

This is a stopped-writer synthetic application upgrade, not a rolling upgrade,
database-engine upgrade, rollback guarantee or recovery test with concurrent writes.
The baseline artifact is used only in disposable test environments and retains its
previously documented Spring MVC advisories.

References:
- [Boot 4 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Managed versions](https://docs.spring.io/spring-boot/4.0/appendix/dependency-versions/coordinates.html)
- [Jackson compatibility](https://docs.spring.io/spring-boot/4.0/reference/features/json.html)
