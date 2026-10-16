# Development

Use Java 17 and the checked-in Maven wrapper. Run `./mvnw verify` for behavioral
changes. Keep changes focused on a user-visible behavior or a concrete engineering
problem; commit messages should describe that change.

The `test` profile defaults to an isolated H2 in-memory database. To test PostgreSQL,
follow [the disposable-database setup](docs/postgresql.md). The same API tests must
run on both engines; do not substitute an H2 compatibility mode for a real PostgreSQL
run. Use `RECON_TEST_DB_VENDOR=PostgreSQL` so the suite checks the actual engine.

The secured-profile HTTP tests generate ephemeral RSA keys and synthetic JWTs;
no external issuer or private key file is needed. Keep default-demo tests as well as
authenticated tests. A rejected token, scope or actor must leave state unchanged.
After packaging/security changes, also run the [secured JAR check](docs/secured-package-checks.md)
on H2 and a disposable PostgreSQL database; it requires Python 3 and OpenSSL 3.
For persistence/migration changes, run `python3 scripts/restore_smoke.py` after
packaging when Docker is available. It owns two disposable PostgreSQL containers;
see [the restore protocol](docs/backup-restore.md) for scope and cleanup.
For review-interface changes, package the JAR and run the
[browser workflows](docs/review-interface.md#reproduce-browser-checks). Preserve
explicit reload after a conflict or ambiguous save, exact cursor/amount decoding,
literal note rendering and default secured-profile denial of demo assets.

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

For secured UI changes also run `npx playwright test --config=playwright.secured.config.js`
from `browser-tests`. Keep bearer headers out of traces, logs and artifacts.
