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
