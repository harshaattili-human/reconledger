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

Interview exercise: why should a key-rotation test check tenant and scope enforcement
again, and why does rejecting a removed key still fail to prove per-user revocation?
