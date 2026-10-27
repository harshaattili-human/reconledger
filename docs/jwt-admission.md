# JWT decoding admission

The MVC concurrency budget runs after authentication. It cannot bound callers
already parsing tokens, waiting for Nimbus's key-cache lock or verifying signatures.
The secured profile now wraps its decoder with a nonblocking semaphore before
any of those operations. Both pinned-key and JWKS modes use this wrapper.

`recon.security.max-concurrent-decodes` defaults to **32** and accepts integers
1..1000. Invalid values prevent secured configuration from starting. The default
is an illustrative bound, not a measured throughput or safe deployment capacity.
For example, `--recon.security.max-concurrent-decodes=8` selects eight simultaneous
decode calls. One application normally owns one decoder; separate application
contexts, processes and replicas each have their own allowance.

When all permits are held, another bearer decode returns the existing sanitized
503/no-store response before entering Nimbus. No Retry-After, automatic retry or
queue is introduced. The failure is infrastructure unavailability, not a claim
that the token is invalid. Once admitted, invalid tokens still produce 401 and
valid tokens still need the existing scopes and tenant claims. Success, invalid
claims, key-fetch failure and unexpected delegate exceptions release the permit
in `finally`. The permit lasts only through decoding, not the subsequent MVC work.

Missing bearer credentials and malformed bearer-header transport errors do not
enter the decoder. Health without a bearer header remains available. Supplying a
bearer header to a public route can still trigger authentication and this budget.
The default unauthenticated demo has no JWT decoder and is unchanged.

## Tradeoffs

Admission happens before a tenant or subject is trusted, so all callers share the
same allowance. A valid cached token can be rejected while other tokens occupy it.
There is no tenant fairness, reservation, identity cache, rate limit or distributed
coordination. The existing MVC budgets still apply after successful authentication.
Repeated invalid tokens can consume CPU; this bounds simultaneous decoder work,
not its arrival rate, connection/header buffers or total servlet threads.

The [JWKS fetch deadline](jwks-deadline.md) bounds one fetch. This wrapper bounds
the number of admitted calls, including cache-lock waiters, but not how long each
one waits or runs. A stuck delegate retains its permit until it exits. This avoids
returning capacity while synchronous work still runs; there is no unsafe thread
termination or whole-request deadline claim.

## Verification

```sh
./mvnw -Dtest=JwtDecodeAdmissionTest,JwksRotationHttpTest,AuthenticatedApiTest test
./mvnw verify
```

A latched 12-caller unit fixture admits exactly three delegate calls while nine
reject before the held calls finish. A further three held recovery calls verify
all permits can be reused together. Another case preserves invalid-token,
infrastructure and unexpected-error types, then verifies recovery. Existing trust
configuration checks now reject zero, negative and over-maximum capacities.

A real RS256/embedded-Tomcat/JDBC workflow configures one slot, warms key A, then
holds a key-B refresh. A request using cached A returns sanitized 503 without an
extra fetch or batch write. Unauthenticated health and missing-bearer 401 remain
available. Releasing the fetch allows the admitted write, then an explicit retry
of the rejected key creates exactly one batch with a successful replay. Invalid
JWTs return 401 again after capacity recovers. Tokens are ephemeral synthetic
fixtures and are not logged or committed.

Implementation `62d1d1d2577dd711276ba00f85c19d59aa133437` passed all five jobs in
[run 40575329332](https://github.com/harshaattili-human/reconledger/actions/runs/40575329332).
H2 `122440108635` and PostgreSQL `122440108571` each passed the same 114 cases,
zero failures/errors/skips, not 228 distinct cases. This adds two admission unit
cases and one secured workflow; configuration assertions extend an existing case.
The final head strengthens simultaneous recovery without changing runtime policy.

Both database jobs passed existing packaged default/secured restart, pinned-key
replacement and retained Boot 3 data upgrades. Browser `122440108604` passed 30
existing Chromium executions; restore `122440108805` passed. Audit
`122440108823` passed its configured inventory/advisory gate. Packaged checks use
the default allowance but do not saturate it. Java/database execution was hosted;
local parent POM resolution remains unavailable. Local whitespace/doc links passed.
These are correctness fixtures, not load, production or external-issuer evidence.

Spring's [JWT authentication provider](https://github.com/spring-projects/spring-security/blob/7.0.6/oauth2/oauth2-resource-server/src/main/java/org/springframework/security/oauth2/server/resource/authentication/JwtAuthenticationProvider.java)
distinguishes invalid JWTs from decoder infrastructure failures; the existing
bearer handler sanitizes the latter.

Optional exercise: why must this budget precede tenant authentication, why can a
cached valid token still be rejected, and why is a bounded waiter count not a
bounded request duration?
