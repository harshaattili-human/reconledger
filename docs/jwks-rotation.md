# JWKS rotation boundary

The secured profile supports two mutually exclusive RS256 verification modes:

- `recon.security.public-key` pins one X.509 PEM RSA key and changes only on restart.
- `recon.security.jwk-set-uri` reads an operator-selected JWK Set and lets Spring
  Security refresh the cached set when a token presents an unknown key ID.

Issuer, audience, expiry, not-before, bounded subject, tenant membership claim and
scope checks are identical in both modes. A remote set cannot expand the accepted
algorithm beyond RS256. The URI must use HTTPS, except that HTTP loopback is allowed
for the repository's self-contained test. User information and fragments are
rejected, and requests cannot choose or override the URI.

## Rotation check

`JwksRotationHttpTest` starts a loopback JWK endpoint and the actual embedded Tomcat
application with the secured profile. It publishes key A and creates a tenant-A
batch. It then replaces the set with key B. A B-signed token with an unknown `kid`
causes another JWK fetch and creates tenant-B data. The check also verifies:

- B cannot read the known tenant-A batch while a tenant-A token signed by B can;
- the removed A key, RS512 and the reserved `demo` tenant receive 401;
- those rejected writes reserve no idempotency key, so a valid B request can use it;
- a valid but unscoped B token receives 403.

This is deterministic protocol evidence with ephemeral synthetic keys. It is not an
external identity-provider integration, a rotation-frequency recommendation or a
revocation guarantee. Immediate cached-key and endpoint-failure behavior is checked below. Cache expiry,
TLS/DNS, redirects, issuer discovery, overlapping-key rollout, provider membership
administration and multi-process timing still require separate verification. Removing
a signing key is also a coarse issuer action, not user-level token revocation.

Spring Security documents automatic key rotation for its JWK Set resource-server
mode and exposes the RS256 builder constraint used here:

- <https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html>
- <https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/oauth2/jwt/NimbusJwtDecoder.JwkSetUriJwtDecoderBuilder.html>

## Observed verification — October 26, 2026

The first implementation run, `40330838814`, compiled and passed the new JWKS HTTP
case on both database jobs, but failed one pre-existing configuration assertion on
each. The refactor loaded the deliberately invalid PEM before rejecting the test's
blank issuer, changing established startup-validation order. Commit
`88e25ffd1c431ad42a7ea5fa5ee3bd651dfdb270` restores issuer/audience validation before
trust-material loading; no token policy or JWKS behavior changed.

[Run 40331965601](https://github.com/harshaattili-human/reconledger/actions/runs/40331965601)
then passed all five jobs. H2 job `121603415996` and PostgreSQL job `121603415636`
each ran the same 93 cases with zero failures, errors or skips; these are 93 cases on
two engines, not 186 distinct cases. The new test used embedded Tomcat, real JWT
decoding and JDBC against each engine. Existing packaged default/secured, pinned-key
restart and Boot 3 retained-data upgrade workflows also passed on both engines.

Browser job `121603416014` passed 12 demo, eight secured and ten repeated Chromium
executions. Restore job `121603415793` passed the existing PostgreSQL 16.15 rehearsal.
Audit job `121603416071` inventoried 73 packaged libraries and returned zero advisory
entries in that scan; this is not a vulnerability-free claim. Hosted execution used
Java 17.0.20.1, Spring Boot 4.0.8 and Ubuntu 24.04. Local whitespace, documentation
links and Python syntax checks passed; Java execution was hosted because the Boot
parent POM was not cached locally and Maven Central DNS was unavailable.

Interview exercise: why should a key-rotation test check tenant and scope enforcement
again, and why does rejecting a removed key still fail to prove per-user revocation?

## Outage and invalid-response contract

Two additional embedded-Tomcat workflows exercise the real decoder against an
operator-controlled loopback endpoint. A fresh application context gives each case
an empty process-local decoder cache. A cold 503 must reject the write; restoring
the endpoint lets the same idempotency key create exactly one batch. After warming
key A, a 503 does not prevent a cached-A read and must cause no additional fetch.
An unknown B key during that outage must fail without inserting a batch; A remains
usable after the failed refresh. Restoring a B-only set permits B, rejects removed
A, and preserves cross-tenant 404 behavior.

The second workflow supplies malformed JSON, then a valid empty key set, and finally
key B. Failed writes must not reserve their key; the final create and replay must
produce exactly one batch. The empty set is a successful key-set replacement, so
previously cached A must then fail too. Endpoint fetch counters distinguish a cache
hit from a successful network call. These checks do not wait for cache expiry or
change the framework cache/transport configuration.

Run `./mvnw -Dtest=JwksRotationHttpTest test` on H2 or with the PostgreSQL test
variables. Java execution is not available locally because the Boot 4.0.8 parent POM is absent from the Maven cache.

The Spring Security 7.0 [JWT documentation](https://docs.spring.io/spring-security/reference/7.0/servlet/oauth2/resource-server/jwt.html)
describes an in-memory key-set cache and configurable HTTP/cache adapters. This
fixture verifies immediate cache/refresh behavior of the resolved dependencies; it
makes no measured expiry-duration, stale-cache, outage-SLA or user-revocation claim.
DNS/TLS, slow responses, redirect policy and simultaneous refresh pressure remain
separate verification work. No real issuer or token is used.

### Failure found and corrected

Initial run `40526948454` failed both new workflows on H2 and PostgreSQL:
endpoint 503 and malformed JSON escaped through `AuthenticationServiceException`
as HTTP 500. The container logged nested exceptions, including the upstream URI
and body. No token or private key was logged by the synthetic fixture.

The bearer filter now uses an explicit failure handler. Verification infrastructure
failures return a fixed 503 problem body with `Cache-Control: no-store`, no bearer
challenge and no invented retry delay. Invalid signatures/claims/unknown keys in a
successfully fetched set still use Spring's 401 bearer challenge; scope failure stays
403. The handler never logs or echoes remote exception details. Existing request
completion diagnostics retain the request ID, route and status. Rejected requests
cannot reach MVC, consume an API admission budget or mutate JDBC state.

The tests now distinguish unavailable verification (503) from a known unusable token
(401), assert redaction and headers, and retain the initial failure. Cached-key
availability does not mean key-removal revocation is immediate or globally coordinated.

### Observed correction — October 27, 2026

Correction `651d9415c082c15a05606faede735b83c498a541` passed all five jobs in
[run 40527685155](https://github.com/harshaattili-human/reconledger/actions/runs/40527685155).
H2 job `122271594894` and PostgreSQL job `122271595678` each passed the same 95 cases
with zero failures, errors or skips (two added workflows, not 190 distinct cases).
All three JWKS workflows passed on each engine. The captured-log assertions found
neither the synthetic upstream-body marker nor an escaped servlet exception, while
the HTTP checks verified fixed 503 bodies, no-store, no challenge/retry delay, 401
for unusable keys and recovery with one stored batch.

Both database jobs also passed the existing default/secured packaged checks, pinned
key replacement and retained Boot 3 data upgrade. Browser job `122271594593` passed
12 demo, eight secured and ten repeated Chromium executions. Restore job
`122271594899` passed the existing PostgreSQL rehearsal. Audit job `122271595088`
inventoried 73 packaged libraries and zero advisory entries in that dated snapshot;
this is not a vulnerability-free claim.

Execution used H2 2.3.232, PostgreSQL 16.15, Boot 4.0.8 and Ubuntu 24.04; Java resolved
to 17.0.21 for H2/audit and 17.0.20.1 for PostgreSQL. These hosted runner resolutions
are recorded rather than described as identical environments. Local whitespace and
documentation checks passed. JWKS failure injection remains embedded HTTP evidence;
packaged secured scripts still use pinned keys.

Exercise: why can a cached key keep working during an issuer outage, while an unknown
key requires a 503 rather than evidence that the token itself is invalid?
