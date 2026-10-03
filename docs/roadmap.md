# Next work

The first milestone is intentionally a small backend service. Priorities below are
planned work, not claims of completed features.

1. Run the same API and race/rollback tests against PostgreSQL in CI. Verify Flyway,
   concurrent unique-key handling and decimal persistence on that engine.
2. Add bounded audit pagination and evaluate a batch-list/filter endpoint with stable
   ordering. Measure query behavior at the existing 500-per-side input limit first.
3. Add authenticated reviewer identity and authorization. Define tenant boundaries,
   scoped idempotency keys and access tests before any multi-user deployment.
4. Enforce request byte limits and rate limits; define retention and database backup
   behavior. Add structured diagnostics that do not log sensitive input values.
5. Build a small review interface with keyboard navigation, conflict recovery and
   accessible evidence comparison. Keep source corrections separate from review notes.

Before adding fuzzy matching or ML, define a labeled synthetic evaluation set with
held-out ambiguous cases and a deterministic baseline. Suggested pairs must retain
their evidence and require review. Do not use a model score as a reason to hide a
duplicate-reference exception.
