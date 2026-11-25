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

Configuration bounds are covered in the secured API configuration test. The
[packaged HTTPS rehearsal](jwks-https-package.md#enabled-fetch-interval) now includes
enabled phases under both cache policies. This remains a local synthetic issuer,
not external identity-provider verification. Local Maven execution on November 24, 2026 was
blocked before compilation by an uncached Boot parent. Hosted results are recorded
in the pull request after execution.

Interview exercise: why can blocking an unknown-key fetch make a previously valid
known-key token temporarily unavailable, and why must failed attempts spend the
same issuer budget as successful ones?


## Executable-JAR interval rehearsal

`python3 scripts/jwks_smoke.py --database h2` (or `--database postgres` with a
configured disposable database) also runs the enabled interval. Each cache policy
starts a fresh JVM with a 10,000 ms interval and a matching certificate trusted by
an explicit temporary JVM truststore. It warms key A, creates a synthetic batch
and review, publishes B, and submits three B creates plus one B review within the
interval. All must receive sanitized 503/no-store with no issuer fetch. A known-A
read must succeed with framework caching or return 503 with the explicit cache.
Health remains public. The fixture fails if it misses the ten-second window.

After waiting 10.1 seconds following that phase, it makes one create with the same
previously rejected idempotency key. A 201 and exactly one additional HTTPS fetch
establish recovery without a JVM restart. The old review version and complete audit
must remain unchanged; an explicit replay returns the same batch, then one review
advances the version and audit. The script never automatically retries a POST.
Receipts retain the policy, configured interval, observed blocked-phase duration,
fetch counts and final version. These durations are fixture checks, not latency or
throughput measurements. Exact nanosecond boundaries remain controlled-clock unit
evidence. Restart reset, fairness and an external issuer remain outside this phase.
