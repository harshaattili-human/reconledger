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
rejects supplied actor labels and derives identity from the verified JWT. This
increment does not change token/issuer parsing, migrate existing data, constrain
internal trusted ReviewCommand callers or promise rendering of every Unicode
character. No schema, migration or dependency change is required.

## Verification

Run `./mvnw verify` on the default H2 test profile and the disposable PostgreSQL
configuration in [postgresql.md](postgresql.md). Two validator cases cover invalid
positions and accepted scalar values. A default-demo MockMvc case rejects malformed
actor labels and round-trips an accented/emoji label. A secured embedded-Tomcat
case sends literal JSON escapes over HTTP, checks rejection without state/audit
mutation or input reflection, then stores and reads a combining/accented/emoji note
through audit and resolved-result responses. These are synthetic fixtures.

Local whitespace checks passed. Full Java/database execution is pending hosted CI;
no before-change database failure or replacement behavior has been measured here.
Existing packaged checks exercise their prior inputs, not these new Unicode cases.

Optional exercise: why must a malformed surrogate be sent as a JSON escape to test
server validation, rather than first passing it through a client UTF-8 encoder?
