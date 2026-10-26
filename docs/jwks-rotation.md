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
revocation guarantee. Cached keys, endpoint outages, TLS/DNS, redirects, issuer
discovery, overlapping-key rollout, provider membership administration and
multi-process cache timing still require deployment-specific verification. Removing
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
