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
for all five outcomes, and results ordered by reference using case-sensitive ASCII
order (the identifiers permit ASCII characters only). Source evidence within each
side is ordered by record ID under the same rule. This order is independent of the
database locale. Each result has its own
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

## Read audit history

`GET /api/results/{id}/events?limit=50&afterSequence=0` returns one page, or 404 for
an unknown result. Each event includes sequence, result ID, actor, from/to state,
resulting version, note and UTC timestamp. Matched results cannot be reviewed and
return an empty page.

| Parameter | Contract |
| --- | --- |
| `limit` | Default 50; integer from 1 to 200 |
| `afterSequence` | Default 0; nonnegative signed 64-bit integer; exclusive lower bound |

Invalid values return HTTP 400 with `application/problem+json`. Omitted or empty
parameters use their defaults. Sequences are database-wide integers, so gaps are
normal. Use the returned value; do not calculate it from a page number or row count.

The response is an object with `events` and `nextAfterSequence`:

```json
{
  "events": [{
    "sequence": 12,
    "resultId": "RESULT_ID",
    "actor": "demo-reviewer",
    "fromState": "OPEN",
    "toState": "IN_REVIEW",
    "resultingVersion": 1,
    "note": "Checking the synthetic source file.",
    "createdAt": "2026-10-04T12:00:00Z"
  }],
  "nextAfterSequence": 12
}
```

This illustrative `limit=1` response indicates that another event was visible.
Request the same result ID with `afterSequence=12` to continue. Events are ordered
by sequence ascending; the cursor identifies the **last returned** event, not the
extra event fetched to detect another page. A final page has `nextAfterSequence: null`,
even when it contains exactly `limit` events. An empty page is
`{"events":[],"nextAfterSequence":null}`.

Each request reads committed history; the page sequence is not a frozen snapshot.
New reviews can appear on later pages. A null cursor means there were no further
visible events at that read, not that the result can never receive another review.
To poll again, retain the last event sequence (or 0 if none) and use it as
`afterSequence`. Cursors must be kept with their result ID and retained database;
they are not global change-stream positions or authorization tokens.

**Migration from the initial prototype:** this endpoint previously returned a bare
array containing all events. Callers must now read `response.events` and follow
`nextAfterSequence` until null. The demo and restart scripts use the new contract.

## Health

`GET /actuator/health` reports `{"status":"UP"}` when healthy. Internal component
details and other actuator endpoints are not exposed by the default configuration.
