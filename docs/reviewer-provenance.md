# Reviewer identity in retained audit history

An actor string could previously be either a caller-supplied demo name or a JWT
subject. A subject also belongs to an issuer's namespace: the same string from
two issuers does not establish the same person. New review events now preserve
how the server obtained the actor and, for verified tokens, its issuer.

| actorSource | actor | actorIssuer |
| --- | --- | --- |
| DEMO_LABEL | Trimmed caller-supplied name | null |
| VERIFIED_JWT | Verified token subject | Exact validated issuer string |
| LEGACY_UNKNOWN | Original retained actor | null |

The events response adds `actorSource` and nullable `actorIssuer`; existing fields
and pagination remain. Review input does not accept either field. Spring's existing
unknown-field rejection returns 400 for attempted provenance injection in both
profiles. `ReviewIdentity` creates an internal command after authentication; the
same transaction writes review state, version, note and identity metadata.
Secured issuer configuration now fails startup above 2,048 characters to match
the database column. No URL normalization is applied to the stored issuer string.

The local UI displays these distinctions and inserts issuer text literally. It
remains demo-only; displaying a verified event is not browser authentication.
No raw bearer token, signing key, email lookup or display-name resolution is stored.

## Retained data

SQL migration V5 adds two columns and a constraint: verified subjects require a
nonblank issuer; demo/unknown actors require null. Every existing event becomes
`LEGACY_UNKNOWN`, including prior secured writes and events in private tenants.
Neither its actor text, tenant nor timestamp proves which issuer authenticated it.
No name or historical evidence is rewritten. V5's default also labels old direct
SQL writers as unknown rather than granting verified provenance.

Stop writers and take a tested backup before migration. H2 DDL may commit in
separate steps; these tests do not establish online migration or crash rollback.
V5 is versioned SQL and has a Flyway checksum; do not edit it after release.
The populated V1 upgrade compares every original event column, and a separate V4
upgrade retains private-tenant history and checks invalid source/issuer combinations.

These fields describe the application's authentication decision at write time.
They do not prove a human performed the action, that the subject remains a tenant
member today, or that a database administrator has not altered the row. Direct
JDBC/in-process code is trusted. Automatic key rotation, revocation, external issuer
configuration, tamper-resistant storage and tenant identity migration remain open.
Changing issuer must not silently transfer tenant access or relabel old events.

## Checks

Run `./mvnw verify` on H2 and PostgreSQL, then the default/secured package checks,
PostgreSQL restore rehearsal and browser commands documented in CONTRIBUTING.
Existing real JWT HTTP checks now assert exact issuer/subject, reject client fields
and preserve state after rejection. Three new Java cases cover the V4 migration,
same subjects in distinct issuer namespaces, and missing/invalid trusted context.
The latter two are internal fixtures, not signature or provider-integration tests.
Package checks assert the metadata survives process restart and pinned-key change;
restore compares every event column. Browser checks add an explicitly injected
display fixture alongside live demo-label assertions.

References: [JWT subject semantics, RFC 7519 §4.1.2](https://www.rfc-editor.org/rfc/rfc7519.html#section-4.1.2)
and [Spring JWT resource-server validation](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html).
Interview exercise: why is `(issuer, subject)` useful for new events but insufficient
to reconstruct the provenance of older actor-only rows?

Observed October 15: implementation `6c44a980` passed
[run 38805939498](https://github.com/harshaattili-human/reconledger/actions/runs/38805939498),
with 67 cases on each database, 12 Chromium executions (six workflows at two
widths), both default/secured package workflows and PostgreSQL restoration.
The [verification record](verification.md) lists jobs and environment. All six
screenshots were inspected; the
[browser report](https://github.com/harshaattili-human/reconledger/actions/runs/38805939498/artifacts/12006437317)
is retained for 14 days. Reproduce after expiry with the documented commands.
