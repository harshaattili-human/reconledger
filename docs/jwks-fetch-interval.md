# Minimum interval between JWKS fetch attempts

Sequential unknown-key tokens can trigger repeated issuer requests even when decoder
concurrency is one. The optional `recon.security.jwks-min-fetch-interval-millis`
limits the start rate of outbound JWKS attempts for one decoder. Default 0 disables
it; values 1..60000 enable a minimum interval in milliseconds. Invalid values prevent
secured startup. Pinned-key mode has no outbound fetches and does not use the budget.

The first attempt is admitted immediately. Admission uses synchronized monotonic
elapsed time, measured from the previous admitted attempt's start. An HTTP error,
invalid response or timeout still spends that attempt. Rejected attempts neither
wait nor move the next eligibility time. There is no automatic retry, negative-key
cache, caller-ID map or background work. Cached-key decodes do not spend an allowance.
The existing complete-fetch deadline, byte bound, redirect policy and decoder
concurrency cap continue to apply. This interval does not cap concurrent fetches
by itself and is not a total authentication deadline.

A blocked fetch is verification infrastructure unavailability, not proof that a
JWT is invalid. It follows the existing sanitized 503/no-store failure path, with
no Bearer challenge or Retry-After. The next eligible attempt can discover a new
key; successful fetches still require all normal signature and claims validation.
A successful fetch that lacks the requested key still yields invalid-token 401.

## Availability and rotation tradeoffs

This setting is off by default because suppressing issuer requests can also delay
legitimate rotation. With the optional explicit cache age, Spring invalidates that
cache before an unknown-key refresh. If the interval blocks the fetch, even a
previously cached valid key becomes unavailable until a later fetch is eligible.
The default framework cache retains its separately tested known-key continuity.
Neither policy uses expired keys as a fallback. A malicious caller can consume the
shared allowance at each boundary; this is not fair scheduling or reserved capacity.

Choose the interval with the issuer's rotation procedure and outage policy. A new
key ID may be delayed by the remaining interval; replacing material under the same
ID still depends on cache expiry as documented in [cache age](jwks-cache-expiry.md).
A shorter cache TTL than fetch interval can create additional unavailability.
Restart resets the budget. Replicas/independently constructed decoders have separate
budgets. No external issuer, membership, user revocation or production capacity is
established by this control.

## Verification

```sh
./mvnw -Dtest=JwksFetchIntervalTest,JwksCacheDecoderTest,JwksFetchIntervalHttpTest,AuthenticatedApiTest test
./mvnw verify
```

Synthetic controlled-clock unit cases check the exact boundary, signed-long clock
wrap, no renewal on rejection, disabled behavior and eight simultaneous contenders
with one admitted attempt. Real Nimbus decoders and loopback HTTP run the same
sequence under both default and explicit-cache policies: warmup, eight blocked
unknown-key attempts, known-key continuity or documented unavailability, a new-key
rotation at the exact boundary, an HTTP 503 attempt, eight blocked retries and
successful recovery. Four outbound requests are expected per policy: warmup,
rotation, failed fetch and recovery. This is a correctness fixture, not a timing or
load measurement. No keys or tokens are persisted.

A secured embedded-Tomcat test enables the real 60-second setting, verifies three
unknown-key POSTs return the sanitized 503/no-store contract without a challenge
or retry header, checks that no idempotency key is reserved, and confirms the
default cached known key still reads without another fetch. Controlled-clock
decoder cases above cover recovery without sleeping through the production interval.

Configuration bounds are covered in the secured API configuration test. Existing
packaged HTTPS and API workflows run with the interval disabled; enabled behavior
is verified at the real-decoder/transport and embedded HTTP layers, not by an enabled packaged launch
or external identity provider. Local Maven execution on November 24, 2026 was
blocked before compilation by an uncached Boot parent. Hosted results are recorded
in the pull request after execution.

Interview exercise: why can blocking an unknown-key fetch make a previously valid
known-key token temporarily unavailable, and why must failed attempts spend the
same issuer budget as successful ones?
