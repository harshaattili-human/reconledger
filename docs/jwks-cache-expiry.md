# Explicit JWKS cache age

An issuer can remove a signing key while the application still has that key cached.
The new optional `recon.security.jwks-cache-ttl-seconds` sets an explicit successful-
load lifetime for JWKS mode. Set 1..3600 seconds; 0 (the default) retains the existing
Nimbus cache behavior. Negative values and values above 3600 prevent secured startup.
Pinned-key mode does not use this cache. Changing the setting requires restart.

The explicit cache holds one JSON string for the configured endpoint. It uses
monotonic elapsed time and expires at the TTL boundary; reads never renew its age.
The TTL starts after a successful fetch and parse. HTTP Cache-Control cannot extend
it. A request after expiry must retrieve the key set before decoding; an unavailable
or malformed endpoint yields sanitized 503, not a decision using an expired key.
A successfully fetched set without the token's signing key yields 401. Existing
issuer/audience/expiry/tenant/scope checks still apply.

There is an availability tradeoff beyond the TTL: Spring's unknown-kid path explicitly
invalidates a supplied cache before refreshing. If that refresh fails, even an
otherwise fresh previously known key must be fetched again. The default Nimbus
cache path retains its existing tested cached-key continuity. Choose the optional
policy deliberately; it is more conservative during an outage. No failure/negative
cache or backoff is added. Repeated unknown IDs can cause repeated retrievals, and
short lifetimes increase issuer load. The existing decoder admission cap bounds
simultaneous decodes; it does not fairly schedule requests or impose a total decode
latency deadline.

This is lazy expiration on use, not background refresh or a promise that every
request stops accepting a removed key at an exact wall-clock instant. A decode
already using a captured key can finish. Workers cache independently, an issuer may
still return an old key, and individual-user revocation/membership changes are not
checked. Key removal affects tokens signed by that key; it is not per-user logout.

## Reproduce

```sh
./mvnw verify
python3 scripts/jwks_smoke.py --database h2
# With a disposable PostgreSQL database configured as documented:
python3 scripts/jwks_smoke.py --database postgres
```

Three cache cases use a controlled monotonic clock for the exact boundary, signed-
long wrap, no read renewal, load failure/recovery, timestamp after loading, and eight
simultaneous misses. A real Nimbus decoder/loopback HTTP case advances that cache
clock while JWT wall-clock expiry remains in the future. It verifies known-key
removal without a new-kid trigger, endpoint outage, malformed replacement, recovery,
and the conservative unknown-key invalidation behavior.

The executable-JAR HTTPS rehearsal sets five seconds only for its valid-certificate
phase and waits through the real TTL. It checks removed-key 401, cached continuity
before expiry, expired-cache 503 for create/review, unchanged audit/version, and
new 201 creation with previously rejected idempotency keys. H2 and PostgreSQL use the
same synthetic workflow; this is no external-provider or production-latency claim.
Other packaged/security checks retain the default cache policy.

Local Maven execution is blocked by an uncached Boot parent; compilation and
application evidence must come from hosted CI. Python syntax and diff checks run
locally. Hosted results will be recorded after completion.

References: [Spring JWT cache customization](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html#oauth2resourceserver-jwt-timeouts)
and [Spring Cache contract](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/cache/Cache.html).

Interview exercise: why can a removed signing key remain usable until cache refresh,
and why does a shorter TTL trade outage availability for fresher key selection?

## Hosted evidence — October 28, 2026

Implementation `8aa452da8f1a9b6344e39223a7e861534ea0f13c` passed all five jobs in
[run 40694890756](https://github.com/harshaattili-human/reconledger/actions/runs/40694890756):
H2 122838905164, PostgreSQL 122838904847, browser 122838905196, restore 122838904944
and Java audit 122838904936. The same 118 Java cases passed on each database
(H2 2.3.232 and PostgreSQL 16.15), including four new cache/decoder cases. These
are 118 distinct cases, not 236. Default and secured packages, pinned-key replacement,
retained Boot 3 upgrade, PostgreSQL restore, and the existing browser workflows passed.

Both executable HTTPS workflows observed 0 HTTP JWKS requests for an untrusted
certificate, 0 for a trusted certificate with a wrong host, and 8 in the valid-host
phase including expiry/outage/recovery. The [receipts](../reports/jwks-cache-expiry-2026-10-28.json)
retain JAR hashes, environments, TTL and declared checks. Java 17.0.21, Python
3.12.3, OpenSSL 3.0.13 and hosted Ubuntu 24.04 were used. The Java audit inventoried
73 packaged libraries with zero advisory entries in that snapshot; this is not
vulnerability-free or external-issuer deployment evidence. Local syntax/whitespace
checks passed; Java/database execution remained hosted.

## Concurrent expiry regression

`JwksCacheDecoderTest.concurrentExpiredKnownKeyDecodesWaitForOneRefresh` warms a
real Nimbus decoder against a synthetic loopback HTTP key endpoint, advances the
controlled monotonic clock exactly to the configured expiry, and releases eight
ready executor callers together. The endpoint holds the refresh response behind a
latch. Every caller must remain incomplete while that response is held; after
release all eight must verify successfully with exactly one additional HTTP fetch.
A subsequent decode must reuse the refreshed cache without another fetch.

This checks known-key expiry coalescing and absence of stale completion in this
fixture. It does not establish fairness, a maximum waiting duration, unknown-key
storm behavior, external issuer availability, or a load/throughput result. The
fixture uses one shared decoder and the explicit cache-age mode; default framework
caching and separate processes are outside it. Signing keys and tokens stay in
memory. No runtime policy changes.

Reproduce with `./mvnw -Dtest=JwksCacheDecoderTest test`. On November 19, 2026,
local execution was blocked before compilation by Maven Central DNS resolution
and an uncached Spring Boot parent. Hosted CI results must be checked before
claiming this regression passes.

## Unknown-key refresh pressure

The real-decoder regression
`repeatedUnknownKeyRequestsRefetchWhileKnownKeysReuseTheResult` holds the cache
clock fixed, warms a known key, then submits the same unknown-key token eight
times sequentially through decoder admission capacity one. It requires one fetch
per rejection while known-key requests reuse each successfully fetched set. Finally
the fixture publishes the missing key and checks discovery and subsequent reuse.
This distinguishes unknown-key refresh from TTL expiry and preserves rotation.

This is a synthetic behavioral characterization, not a throughput benchmark or
external issuer test. The expected ten fetches are one warmup, eight missing-key
refreshes and one rotation refresh. Concurrency admission alone cannot bound the
rate of sequential refreshes. An issuer-fetch budget would need an explicit
rotation-delay policy and separate testing before adoption; no such policy is
introduced here. Reproduce with `./mvnw -Dtest=JwksCacheDecoderTest test`.
Hosted verification is required; local Maven resolution has been unavailable.

## Replacement material under the same key ID

`reusedKeyIdRequiresExpiryAndRejectsOldMaterialAfterRefresh` distinguishes a known
key with an invalid signature from an unknown key ID. The loopback fixture warms
one RSA key, then publishes different RSA material under the same ID. At one
nanosecond before the controlled 300-second cache boundary, three replacement-token
decodes must reject without a fetch, while old tokens remain accepted. At the exact
boundary the replacement token must cause one fetch and succeed. Old tokens must
then reject without refetching, and replacement tokens must reuse the new set.
Both tokens remain within their JWT wall-clock expiry throughout the fixture.

This is an explicit-cache, single-decoder behavioral contract, not external issuer
or elapsed-time evidence. It demonstrates why unknown-key refresh coverage alone
cannot establish same-ID rotation behavior. No runtime policy changes, issuer-fetch
budget, background refresh or user revocation are introduced. A future fetch budget
must account for both paths rather than assuming every signature failure fetches.
Reproduce with `./mvnw -Dtest=JwksCacheDecoderTest test`; hosted results belong in the
pull request because local Maven dependencies have been unavailable.
