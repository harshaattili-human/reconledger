# Next work

The project is intentionally a small backend service. PostgreSQL verification was
completed on October 3, 2026: the shared API suite and packaged-app restart checks
pass on both engines. That work also found and fixed locale-dependent response
ordering. See [the evidence](verification.md).

Remaining priorities are planned work, not claims of completed features.

[Reviewer provenance](reviewer-provenance.md) now records the identity source and
verified issuer on new audit events. Earlier rows remain unknown; this does not
replace issuer membership verification or tamper-resistant audit storage.

1. Verify external issuer membership configuration and automatic key rotation/revocation.
   Define tenant administration, export/deletion and operational separation before
   real multi-tenant deployment.
2. Add tenant fairness and edge/concurrency controls beyond the optional
   [process-wide API rate budget](rate-limits.md); define retention and operational
   backup/recovery policy.
   The [logical restore rehearsal](backup-restore.md) checks a quiesced synthetic
   PostgreSQL checkpoint; off-host retention, real recovery objectives and H2 backup
   remain unverified. Add structured diagnostics that do not log sensitive input values.
3. Verify manual assistive-technology behavior and external issuer login for the
   [review workspace](secured-review-interface.md). The opt-in secured screen accepts
   existing tokens; it does not implement provider login. Keep source corrections
   separate from review notes.

The [secured profile](authentication.md) now derives reviewer identity from verified
JWTs and enforces separate read/create/review scopes. [Tenant ownership](tenants.md)
now scopes data and idempotency to a signed tenant claim, with existing rows retained
in demo. External issuer membership configuration remains unverified.

The JSON write endpoints now enforce a 512 KiB body limit before parsing, with
fixed-length and chunked HTTP cases. See [request bounds](request-bounds.md) for
the scope; slow uploads and aggregate concurrency still need controls. The optional process
budget does not bound work across replicas.

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

[Tenant-filtered characterization](tenant-query-characterization.md) now covers
uneven ownership and first/continuation pages. A tenant/currency index alone failed
to fix the large-tenant PostgreSQL scan and was rejected. Before another query/schema
change, compare joint-selectivity statistics and allocation orders; preserve this
baseline and measure planning/write/storage costs as appropriate.

The [joint-statistics experiment](tenant-statistics.md) now compares index/MCV/both
across blocked and shuffled allocations. Only the combination changed the large
rare-currency scan; the common-currency blocked scan remained. Verify the candidate
on ordinary tables with repeated trials and maintenance/write-cost checks before
promoting it. No new index or statistics migration has been released.
