# Preserve review text across storage and responses

Review notes and demo actor labels must contain Unicode scalar values without
U+0000 (NUL). The input validator rejects isolated high/low UTF-16 surrogates,
including malformed escaped pairs, before controller dispatch. Authorized invalid
requests receive 400 and leave review version, resolution and audit unchanged.
It returns fixed validation messages, never the submitted text. Existing security,
requiredness, length and transition checks remain in place.

Valid supplementary characters, accented text, combining sequences, newlines and
tabs remain supported. The validator neither repairs text nor normalizes it; a
combining sequence is retained exactly. Existing length limits still count Java
UTF-16 code units, so a supplementary character consumes two units. NUL is rejected
consistently on H2 and PostgreSQL rather than allowing database-specific outcomes.

This contract applies to JSON review `note` and `actor` fields. Secured mode still
rejects supplied actor labels and derives identity from the verified JWT. The subsequent identity boundary below validates subjects and configured issuers.
Neither change migrates existing data, constrains
internal trusted ReviewCommand callers or promises rendering of every Unicode
character. No schema, migration or dependency change is required.

## Verification

Run `./mvnw verify` on the default H2 test profile and the disposable PostgreSQL
configuration in [postgresql.md](postgresql.md). Two validator cases cover invalid
positions and accepted scalar values. A default-demo MockMvc case rejects malformed
actor labels and round-trips an accented/emoji label. A secured embedded-Tomcat
case sends literal JSON escapes over HTTP, checks rejection without state/audit
mutation or input reflection, then stores and reads a combining/accented/emoji note
through audit and resolved-result responses. These are synthetic fixtures.

Local whitespace checks passed. Offline Maven compilation could not resolve the
uncached Boot 4.0.8 parent. No before-change database failure or replacement
behavior has been measured here.
Existing packaged checks exercise their prior inputs, not these new Unicode cases.

Optional exercise: why must a malformed surrogate be sent as a JSON escape to test
server validation, rather than first passing it through a client UTF-8 encoder?

## Hosted execution

Implementation `1fbbfc447002a8526c28697fa0bbd34c72a506a0` passed all five jobs in
[run 42239336179](https://github.com/harshaattili-human/reconledger/actions/runs/42239336179).
H2 job `127965171605` and PostgreSQL job `127965171768` each passed the same 127
Java cases with zero failures, errors or skips (four added cases, not 254 distinct
cases). This includes valid Unicode audit/resolution readback and unchanged state
after rejected input. Java 17.0.21, Boot 4.0.8, H2 2.3.232 and PostgreSQL 16.15 ran
on hosted Ubuntu.

Existing browser `127965171602`, PostgreSQL restore `127965171322`, packaged
application workflows and Java audit `127965171427` also passed. The new Unicode
payloads are exercised by the Java cases, not by separate packaged/browser fixtures.
Final-head checks are linked from [PR 39](https://github.com/harshaattili-human/reconledger/pull/39).

## Verified identity boundary

The same scalar-value predicate now rejects malformed UTF-16 and NUL in JWT
subjects during claim validation, before an authenticated SecurityContext reaches
read or write handlers. Configured issuers fail startup on those values before
trust material loads. Pinned-key and JWKS decoders share this validation. Existing
subject length, whitespace, issuer equality, scopes and tenant rules remain intact.
Valid combining sequences and supplementary characters retain their exact identity;
normalization or replacement could conflate different subjects and is not used.

A synthetic embedded-Tomcat case signs literal JSON escapes with JCA RS256, checks
five malformed subjects against reads, batch creation and review, then checks
unchanged keys/version/audit and exact valid subject readback in session and audit.
A configuration case rejects four invalid issuer suffixes before key loading.
These add two Java cases; hosted results are recorded in the pull request. The
secured executable-JAR workflow now signs the same five invalid subject forms with
OpenSSL after restarting the current JAR, checks 401 and unchanged version/audit,
then round-trips a combining/emoji subject through session and a persisted review.
This also runs after the older Boot 3 seed JAR is replaced, without requiring the
older binary to implement the new contract. No external issuer, historical-data
migration or new database constraint is claimed.

Reproduce with `./mvnw verify` on H2 and the documented disposable PostgreSQL
profile. Local whitespace checks passed; Maven execution requires the unavailable
cached Boot parent or dependency access.

Exercise: why should an authentication boundary reject an invalid identity instead
of replacing malformed characters before writing an audit event?
