# Design decisions

## Match only when the evidence is unambiguous

The engine groups records by exact, case-sensitive reference, then visits the sorted
union of references. A group with more than one record on either side is always
`DUPLICATE_REFERENCE`. Summing two left records into one right record might hide a
duplicate import, so this version does not infer a many-to-one relationship.

If neither side has duplicates, an absent side becomes `MISSING_LEFT` or
`MISSING_RIGHT`. A single pair is compared with `BigDecimal.compareTo`; equality is
numeric rather than scale-sensitive. Normalization uses `setScale(2, UNNECESSARY)`
and rejects fractional cents instead of rounding. The database uses `DECIMAL(14,2)`.
The same record ID can appear on opposite sides, but not twice within one side.

This is a rules problem with explicit evidence and conservative outcomes. There is
no machine-learning model in ReconLedger. An ML suggestion would need separate
evaluation and could not silently resolve exceptions.

## A retry is identified by both key and content

Input records are sorted by record ID within each side and their decimals normalized.
A versioned, length-prefixed representation includes business date, currency, side,
IDs, references, and amounts. Its SHA-256 digest is stored with a globally unique
idempotency key. The uniqueness scope is this single-user database, not an account.

The first request returns 201. An equivalent retry returns 200 with the same batch
ID and `Idempotency-Replayed: true`. Reusing a key with different content returns 409.
Payload array order and equivalent decimal spelling do not change the digest.
Reference case, business date, currency, IDs and side placement do change it.

The fast lookup avoids unnecessary writes, but it is not the concurrency guarantee.
The database's unique constraint is authoritative. When two requests race, the
losing insert rolls back before reading the winner and checking its digest. The
recovery SELECT deliberately runs outside the failed transaction; PostgreSQL does
not allow ordinary statements in an aborted transaction.

There is no expiry policy in this milestone. Deleting the database also removes its
idempotency memory. A replay returns the batch's **current review state**, not a
byte-for-byte copy of the original response. Original source records and outcomes
are unchanged. This is request deduplication, not a claim of exactly-once delivery.

## Review changes and evidence commit together

Allowed transitions are:

| Current state | Allowed target |
| --- | --- |
| OPEN | IN_REVIEW |
| IN_REVIEW | RESOLVED or OPEN |
| RESOLVED | IN_REVIEW |
| NOT_REQUIRED | None |

Every transition needs a note and the last observed version. The SQL update includes
`WHERE id = ? AND version = ?`; if no row changes, the service reports a conflict.
The state change and audit insertion share one transaction. Tests inject a failure
after the update to verify that both state and version roll back.

Reopening removes the current resolution note, while the previous note remains in
the audit history. Audit events have a database sequence and per-result version;
timestamps alone are not used as ordering guarantees. Sequence gaps after a rollback
are harmless. The actor string is a demo label, not an authenticated identity.

## Keep infrastructure small and explicit

Spring MVC handles HTTP and validation. The reconciliation engine has no database
dependency. `JdbcTemplate` keeps the unique constraint, optimistic update and transaction
boundaries visible. `TransactionTemplate` is used deliberately so duplicate-key recovery
can happen after rollback. Flyway owns schema creation.

Batch reads fetch the header, all bounded source rows, and all results in three queries;
they avoid one source query per result. Records are immutable through this API. A review
response currently reloads its batch to assemble source evidence; this is acceptable
for the 1,000-record bound but should be measured before scaling. Event history is not
paginated yet and can grow with repeated reviews.

The bounded source and result lists are sorted in Java with the same case-sensitive
string order as the engine. The first PostgreSQL run exposed why SQL `ORDER BY`
alone was insufficient: the database's locale put `a-ref` before `A-REF` and `.REF`,
while H2 used a different order. Sorting the fetched lists makes the API contract
independent of that setting. A future paginated batch-list API will need an explicit
database ordering/cursor contract; sorting one page after fetching is not sufficient.

H2 file storage makes the local demo easy to start. PostgreSQL is the next integration
target; matching SQL syntax alone does not validate its locking or migration behavior.

## Interview exercise

Explain why a lookup followed by an insert is insufficient for idempotency. Point to
the unique constraint and the losing transaction's recovery path. Then propose how
keys, source records and reviewer identity would be scoped if multiple customers
shared the service. Implementing that safely requires more than adding a tenant field
to the request body.
