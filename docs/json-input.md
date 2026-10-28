# Reject ambiguous JSON object fields

Reconciliation writes now reject duplicate object field names with the existing
400 malformed-request response. The Jackson streaming parser checks names within
each object before controller dispatch, including nested source records and names
that are equivalent after JSON escape decoding. Identical repeated values are also
rejected: clients must send one value, rather than rely on parser-specific ordering.
A repeated name in separate objects remains valid, as do repeated words in strings.

The policy applies to Boot's application JSON mapper in both demo and secured
profiles. It preserves auto-configured modules, exact-decimal validation, unknown
field rejection and the existing 512 KiB body bound. Security still precedes parsing;
an unauthenticated request receives 401 and a missing write scope receives 403.
Authorized malformed writes receive 400 before reserving an idempotency key or
changing review version/audit. Errors return a fixed message, not parser diagnostics
or the submitted values. Nimbus token/JWKS parsing is separate and unchanged.

This is an intentional tightening of the input contract. A caller that previously
sent repeated fields must correct its JSON. It does not canonicalize Unicode field
names, normalize values or change the rule for duplicate business references in
arrays. Field tracking adds parser work within the existing byte bound; no parsing
throughput or aggregate-memory guarantee is claimed. A custom replacement mapper
must preserve this policy explicitly.

## Reproduce

```sh
./mvnw verify
python3 scripts/smoke.py --database h2
# Follow docs/postgresql.md for a disposable PostgreSQL environment.
python3 scripts/smoke.py --database postgres
```

Three added JUnit cases cover default-demo MockMvc plus secured real HTTP with the
actual decoder and database. Batch variants include conflicting/identical root
currency, nested amount, null overwritten by a value, escaped equivalent amount
names and repeated arrays. Review variants include version, state, note and actor
overwritten by null. Rejection must leave keys unused and review/audit unchanged;
subsequent unique-field requests succeed with verified reviewer attribution.
The packaged check exercises fixed/chunked duplicate root/nested writes against the
actual JAR, verifies unused keys and unchanged version/audit after a rejected review.
Existing fixtures with field names repeated across separate records remain valid.

## Baseline and execution

Test-only commit `849fdc8a740a463ce58f1cf9bc251c4083d6ce3f` exposed the current
behavior in [run 40706869507](https://github.com/harshaattili-human/reconledger/actions/runs/40706869507).
H2 job `122881240054` and PostgreSQL job `122881240556` failed the three new assertions: demo and secured batch
creation returned 201, and the secured review returned 200, where 400 was required.
Each loop stopped at its first failure; this does not establish every variant's
baseline behavior. The remaining 119 cases passed on each engine. Local whitespace and smoke-script
compilation passed; the
Boot parent is not cached locally, so full Java/database execution uses hosted CI.

Implementation follows the [Boot mapper customization extension](https://docs.spring.io/spring-boot/4.0/api/java/org/springframework/boot/jackson/autoconfigure/JsonMapperBuilderCustomizer.html)
and Jackson's `StreamReadFeature.STRICT_DUPLICATE_DETECTION` contract. No schema,
identity policy, migration or dependency changes are required.

Optional interview exercise: why can accepting the last duplicate value produce
different interpretations between a validator, signature layer and business parser?


Implementation `45ade85cf1c1a62112625804b9c2e6e36362c70b` passed all five jobs in
[run 40707446705](https://github.com/harshaattili-human/reconledger/actions/runs/40707446705).
H2 `122883259496` and PostgreSQL `122883259463` each passed the same 122 Java
cases with zero failures, errors or skips, including all variants in the three new
cases. These are 122 cases on two engines, not 244 distinct cases. Both default
packaged checks passed the new fixed/chunked duplicate-field workflow and matched
21 response IDs to redacted completion events across restart. Existing secured
packages, pinned-key and HTTPS JWKS checks, retained Boot 3 upgrade and PostgreSQL
restore `122883260010` also passed.

Browser `122883259307` passed the existing 36 demo, 24 secured and ten repeated
Chromium access-rejection executions (70 executions, not 70 independent workflows).
Audit `122883259742` inventoried 73 packaged Java libraries with zero advisory
entries in the dated snapshot; this does not establish vulnerability freedom.
Java 17.0.21, Boot 4.0.8, H2 2.3.232 and PostgreSQL 16.15 ran on Ubuntu 24.04.
No dependency, schema, identity or query-plan change was needed. Final-head checks
are linked from [PR 36](https://github.com/harshaattili-human/reconledger/pull/36).
