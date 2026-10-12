# Next work

The project is intentionally a small backend service. PostgreSQL verification was
completed on October 3, 2026: the shared API suite and packaged-app restart checks
pass on both engines. That work also found and fixed locale-dependent response
ordering. See [the evidence](verification.md).

Remaining priorities are planned work, not claims of completed features.

1. Verify external issuer membership configuration and automatic key rotation/revocation.
   Define tenant administration, export/deletion and operational separation before
   real multi-tenant deployment.
2. Enforce rate limits; define retention and database backup
   behavior. Add structured diagnostics that do not log sensitive input values.
3. Build a small review interface with keyboard navigation, conflict recovery and
   accessible evidence comparison. Keep source corrections separate from review notes.

The [secured profile](authentication.md) now derives reviewer identity from verified
JWTs and enforces separate read/create/review scopes. [Tenant ownership](tenants.md)
now scopes data and idempotency to a signed tenant claim, with existing rows retained
in demo. External issuer membership configuration remains unverified.

The JSON write endpoints now enforce a 512 KiB body limit before parsing, with
fixed-length and chunked HTTP cases. See [request bounds](request-bounds.md) for
the scope; slow uploads, aggregate concurrency and rate limits still need controls.

The [mixed-history experiment](mixed-prepared-plans.md) now supports a query-scoped
PostgreSQL mitigation for currency browsing. Bound parameters are retained and no
pooled-session setting changes. Re-evaluate the policy with representative allocation
distributions and concurrent traffic; current sequential fixtures do not make it a
general performance guarantee.

Before adding fuzzy matching or ML, define a labeled synthetic evaluation set with
held-out ambiguous cases and a deterministic baseline. Suggested pairs must retain
their evidence and require review. Do not use a model score as a reason to hide a
duplicate-reference exception.

Audit pagination now limits rows in SQL, with an exclusive sequence cursor, a
200-event maximum and documented live-read semantics. Contract tests cover page
boundaries, later reviews, equal timestamps, rollback gaps and reads during an
uncommitted review; the demo resumes a cursor after application restart.

The [secured executable-JAR check](secured-package-checks.md) now covers profile
startup, file-based trust configuration, retained tenant state and manual pinned-key
replacement on restart. External membership, automatic rotation and user revocation
remain separate work; the test signer does not substitute for an identity provider.
