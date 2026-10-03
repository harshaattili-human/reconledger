# Development

Use Java 17 and the checked-in Maven wrapper. Run `./mvnw verify` for behavioral
changes. Keep changes focused on a user-visible behavior or a concrete engineering
problem; commit messages should describe that change.

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
