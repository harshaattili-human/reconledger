# Next work

The project is intentionally a small backend service. PostgreSQL verification was
completed on October 3, 2026: the shared API suite and packaged-app restart checks
pass on both engines. That work also found and fixed locale-dependent response
ordering. See [the evidence](verification.md).

Remaining priorities are planned work, not claims of completed features.

1. Evaluate a query-scoped mitigation for currency browsing against the existing
   automatic-planning baseline. The [mixed-history probe](mixed-prepared-plans.md)
   found that a large rare-currency primer leaves later small pages on a generic
   scan; common or small-page primers kept the identical mixed tail custom.
   Preserve bound parameters and pooled-session settings. Concurrent writes,
   different allocation distributions and planning/index costs remain unmeasured.
2. Add authenticated reviewer identity and authorization. Define tenant boundaries,
   scoped idempotency keys and access tests before any multi-user deployment.
3. Enforce request byte limits and rate limits; define retention and database backup
   behavior. Add structured diagnostics that do not log sensitive input values.
4. Build a small review interface with keyboard navigation, conflict recovery and
   accessible evidence comparison. Keep source corrections separate from review notes.

Before adding fuzzy matching or ML, define a labeled synthetic evaluation set with
held-out ambiguous cases and a deterministic baseline. Suggested pairs must retain
their evidence and require review. Do not use a model score as a reason to hide a
duplicate-reference exception.

Audit pagination now limits rows in SQL, with an exclusive sequence cursor, a
200-event maximum and documented live-read semantics. Contract tests cover page
boundaries, later reviews, equal timestamps, rollback gaps and reads during an
uncommitted review; the demo resumes a cursor after application restart.
