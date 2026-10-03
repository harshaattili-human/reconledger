# Development

Use Java 17 and the checked-in Maven wrapper. Run `./mvnw verify` for behavioral
changes. Keep changes focused on a user-visible behavior or a concrete engineering
problem; commit messages should describe that change.

The `test` profile defaults to an isolated H2 in-memory database. To test PostgreSQL,
follow [the disposable-database setup](docs/postgresql.md). The same API tests must
run on both engines; do not substitute an H2 compatibility mode for a real PostgreSQL
run. Use `RECON_TEST_DB_VENDOR=PostgreSQL` so the suite checks the actual engine.

Use synthetic records only. Never add employer code, customer data, secrets, private
logs or real account identifiers. Local H2 files and Maven build output are ignored.

Preserve these invariants:

- Fractional cents are rejected rather than rounded.
- Duplicate references are not silently paired or summed into a match.
- A batch and all of its evidence commit together.
- Equivalent retries cannot create duplicate batches.
- Review state and audit history cannot commit separately.
- A stale reviewer cannot overwrite a newer decision.

Add tests for changed behavior and document actual verification commands and results.
Do not claim production readiness, performance, independent review or deployment
without evidence. Test counts are coverage examples, not a quality score. Keep AI
assistance and synthetic-data provenance accurate.
