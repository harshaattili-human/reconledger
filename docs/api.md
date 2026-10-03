# HTTP API

Base URL: `http://127.0.0.1:8081`. Requests and responses use JSON; validation and
domain errors use Spring `ProblemDetail` (`application/problem+json`).

## Create or replay a batch

```bash
curl -i -X POST http://127.0.0.1:8081/api/batches \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: example-mixed-batch-v1' \
  --data-binary @examples/mixed-batch.json
```

Use a different key for a genuinely new batch. The key is 8–80 characters from
letters, digits, `.`, `_`, `:`, `-`. Store it before sending the request so a network
timeout can be retried safely.

| Field | Contract |
| --- | --- |
| businessDate | Required ISO local date, such as `2026-10-03`; no timezone |
| currency | Required three uppercase letters; one label for the entire batch |
| leftRecords / rightRecords | Required arrays, 0–500 records each; at least one record overall |
| recordId | Unique within its side, 1–80 letters/digits or `._:/-` |
| reference | Case-sensitive, same character/length rules as recordId; duplicates become exceptions |
| amount | Required decimal, absolute value at most `999999999999.99`, exactly representable to two decimal places |

Amounts can be decimal JSON numbers or decimal strings. Strings help callers avoid
binary floating-point conversion before transmission. Responses use JSON numbers;
clients should decode amounts as decimals if doing arithmetic. Unknown request fields
are rejected. Identifiers are not trimmed or case-normalized.

The response contains a batch `id`, `businessDate`, `currency`, creation time, counts
for all five outcomes, and results ordered by reference. Each result has its own
`id`, `outcome`, `reviewState`, `version`, optional `resolutionNote`, and original
left/right records. `Location` points to `/api/batches/{id}`.

| Status | Meaning |
| --- | --- |
| 201 | New batch committed; `Idempotency-Replayed: false` |
| 200 | Equivalent key/content replay; `Idempotency-Replayed: true` |
| 400 | Invalid JSON, input bounds, amount, record IDs, or missing/invalid key |
| 409 | Key already belongs to a different normalized batch |
| 503 | Database operation failed; retry creation with the same key |

## Fetch a batch or result

`GET /api/batches/{id}` and `GET /api/results/{id}` return 200 or 404. IDs come from
creation responses; missing IDs do not create resources.

## Record a review transition

```bash
curl -i -X POST http://127.0.0.1:8081/api/results/RESULT_ID/reviews \
  -H 'Content-Type: application/json' \
  -d '{"targetState":"IN_REVIEW","expectedVersion":0,"actor":"demo-reviewer","note":"Checking the synthetic source file."}'
```

Replace `RESULT_ID` with an exception result's ID. A successful transition returns
200 and the updated result with an incremented version. A valid transition and a
current `expectedVersion` are both required. Notes are mandatory for every transition
(1–500 characters after the nonblank check); actor is a self-reported label up to
80 characters. Leading and trailing whitespace is stripped before storage. Resolving
requires `IN_REVIEW` first. See [the state table](design.md).

A stale version or disallowed transition returns 409. Fetch the latest result and
review the intervening changes; do not blindly overwrite with an incremented number.
Unlike batch creation, review submissions do not have idempotency keys. After an
ambiguous network failure, fetch the result and events to determine whether the
transition committed before deciding to submit another transition.

`GET /api/results/{id}/events` returns the ordered audit event array, or 404 for an
unknown result. Each event includes sequence, actor, from/to state, resulting version,
note and UTC timestamp. Matched results start at `NOT_REQUIRED` and cannot be reviewed.

## Health

`GET /actuator/health` reports `{"status":"UP"}` when healthy. Internal component
details and other actuator endpoints are not exposed by the default configuration.
