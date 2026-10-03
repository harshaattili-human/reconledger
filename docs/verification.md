# Verification record

Verified locally on October 3, 2026. This record describes a local prototype;
it does not claim deployment, production traffic or financial accuracy.

## Environment and commands

- Linux x86_64; OpenJDK 17.0.20.
- Maven 3.10.0 through wrapper 3.3.4.
- Spring Boot 3.5.16, JDBC, Flyway and H2.
- Python 3 standard library for the real-HTTP demo and restart check.

```bash
./mvnw -o -B -ntp verify
python3 scripts/smoke.py
```

The final Maven run used the already-populated dependency cache (`-o`). A first run
needs network access; use `./mvnw verify`. Workspace-specific proxy settings used to
download dependencies are outside the repository.

## Results

| Check | Observed result |
| --- | --- |
| ReconciliationEngineTest | 9 tests passed; 0 failures/errors/skips |
| ReconciliationApiTest | 14 tests passed; 0 failures/errors/skips |
| Maven verify | BUILD SUCCESS; runnable `-app.jar` produced |
| Real HTTP demo | All assertions passed for 11 synthetic records across 6 references |
| Process restart with H2 file database | Same batch, source evidence, resolved review state, two audit events and idempotency key recovered |

The engine tests cover decimal scale, negative reversals, all five outcomes,
ambiguous duplicate references, stable ordering, normalized fingerprints, repeated
record IDs, empty/overflow input, case sensitivity, and extreme decimal exponents.

The API tests exercise validation and HTTP errors, six simultaneous submissions
with one idempotency key, two simultaneous reviewers, rejected stale transitions,
matched-result immutability, and rollback after injected result/audit write failures.
The injected database errors are expected test events, not unresolved failures.

The test environment did not support Mockito's default JVM self-attachment. The
tests use its subclass mock maker to spy on the non-final repository; they do not
disable the transaction or database checks. Initial attempts failed before context
startup. The final result above includes the corrected setup.

The packaged application has an explicit `app` classifier so the executable archive
and plain class archive are distinct. The smoke script starts that actual executable
twice, uses an isolated temporary file database, and stops both processes afterward.

## Hosted verification

[GitHub Actions run 37159327161](https://github.com/harshaattili-human/reconledger/actions/runs/37159327161)
passed on October 3, 2026 for source commit
`e9d1157747844f1db3254228db729a66028a128e`.
The GitHub-hosted Linux runner used Temurin Java 17.0.20 and the Maven wrapper.
All 23 tests passed with zero failures, errors or skips. Packaging succeeded, and
both the real-HTTP demo and persistent restart checks passed. The published source
tree was compared with the locally tested tree and matched exactly.

## Not yet verified
- PostgreSQL migrations, transaction behavior and driver path.
- Windows/macOS startup, container deployment or public hosting.
- Load, throughput, large histories, real financial records or real-world matching quality.
- Authentication, authorization, tenant isolation or tamper-resistant audit storage.

The 23 tests and small synthetic demo verify specific behaviors. They are not a
coverage percentage, a benchmark, or proof of production readiness.
