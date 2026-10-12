# Tenant ownership and retries

In the `secured` profile, each valid token now needs one signed `tenant_id` string.
It accepts 1–64 lowercase ASCII letters/digits, `_` or `-`, starting with a letter
or digit. `demo` is reserved and rejected. Missing, malformed or reserved claims
return 401 before any database work. This is a breaking token-contract change from
the initial single-workspace secured profile; scopes and subject checks still apply.

The configured issuer is responsible for issuing the claim only after checking
membership. ReconLedger does not accept a tenant header/body field, perform its own
membership lookup or revoke membership before token expiry. One token selects one
tenant; switching tenants requires another issuer-authorized token. Subject IDs and
tenant IDs are namespaced by the one configured issuer. Changing issuer or reusing
a tenant ID requires an explicit data/identity migration.

## Data access

`recon_batch.tenant_id` is the ownership root. Creation records it from the verified
security context. List filters run in SQL before keyset pagination. Batch detail
checks ownership before reconstructing source evidence; result lookup joins its
batch, and audit reads check the owned result before fetching events. Reviews use
that same lookup plus a tenant condition on the version-checked UPDATE. The API
cannot move batches between tenants, so child ownership cannot change between the
root check and reconstruction through an application request.

Known foreign IDs and nonexistent IDs both return 404 for detail, audit and review
requests with the required scope. Scope failures still return 403. A rejected foreign
review leaves version and audit unchanged. Readers within the same tenant share all
of that tenant's records; there are no per-record owner roles or administrator bypass.
Global numeric sequences remain cursors, not access tokens: gaps can reflect other
tenants' allocations. This does not claim isolation of allocation metadata or timing.

The current synchronous request uses Spring's security context. A secured repository
read without a verified tenant fails with 401 rather than silently using the demo.
Background jobs would need an explicit trusted identity design; do not copy a tenant
from untrusted job data. JDBC credentials and in-process code remain trusted. This is
application access control, not database row-level security against arbitrary SQL.

## Idempotency and migration

V4 retains existing headers, records, results and events in the reserved `demo`
workspace, including rows written through the previous secured profile. No old row
is automatically assigned to an authenticated customer. Historical ownership cannot
be recovered from its self-reported or subject-only actor label.
The default unauthenticated demo reads/writes only `demo`; switching profiles cannot
expose authenticated tenants through the existing demo API. Do not use a real-data
database as a demo database or mistake this for permission to deploy publicly.

The unique constraint is now `(tenant_id, idempotency_key)`. Equivalent retries
within a tenant replay that tenant's batch; changed input conflicts. Two tenants
may use the same key with different payloads and receive distinct batches. Recovery
from a concurrent insert conflict also searches within the current tenant. The
normalized payload fingerprint is unchanged because ownership is in the key scope.

V1 generated different unnamed unique-constraint names on H2 and PostgreSQL. V4 is
a Java Flyway migration that discovers exactly the single-column idempotency
constraint in the current schema, adds tenant ownership and composite uniqueness,
then removes the old constraint. It adds a tenant/sequence browse index. Existing
currency/date indexes remain; their storage/write costs and tenant-filter plans need
measurement before further index changes. Earlier prepared-plan measurements used
unscoped SQL and do not establish performance for this new predicate.

Run migrations with application writers stopped and a tested backup. H2 DDL can
commit separately; the migration is not an online, crash-recovery or automatic
rollback procedure. The existing migration case upgrades populated V1 through V4,
checks evidence retention/default ownership, and verifies both cross-tenant key
reuse and same-tenant uniqueness. As with other versioned migrations, do not edit
V4 after release; Java migrations do not get automatic SQL-style checksum detection.
See [Flyway's Java migration guidance](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/java-based-migrations).

## Checks and limits

`./mvnw verify` includes real HTTP/JWT tests for malformed tenant claims, ignored
headers, rejected body fields, foreign detail/audit/review IDs, interleaved filtered
pages, and six simultaneous retries across two tenants with the same key. A default
HTTP case checks private-tenant rows remain inaccessible from the demo. Migration
checks and the same contracts run on both databases; see [verification](verification.md)
for observed results. No real customer data or external identity provider is used.

External issuer membership configuration, automatic key rotation/revocation, tenant deletion,
retention/export policy, per-tenant rate limits, operational separation and backup
restore remain separate work. The service remains a local synthetic prototype.

Interview exercise: explain why adding a tenant filter to GET endpoints is insufficient
if the idempotency lookup or losing-insert recovery still searches globally.
