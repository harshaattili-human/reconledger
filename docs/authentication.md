# Authenticated reviews

The default loopback demo accepts a caller-supplied `actor`. The optional `secured`
profile verifies bearer JWTs and records the verified `sub` as the reviewer instead.
It separates reading evidence, creating batches and writing review decisions.

Each token must also carry a valid signed `tenant_id`; data access and idempotency
keys are scoped to that tenant. See [tenant ownership and migration](tenants.md).
The issuer must establish membership before signing this claim. Do not expose the
prototype publicly or load real customer data.

## Start the profile

Choose exactly one trust source: an X.509 PEM RSA public key (at least 2048 bits),
or an HTTPS JWK Set URI. Supply the exact issuer and intended audience in either
mode. The service does not issue tokens or store a signing key.

```bash
java -jar target/reconledger-0.1.0-SNAPSHOT-app.jar \
  --spring.profiles.active=secured \
  --recon.security.public-key=file:/absolute/path/issuer-public.pem \
  --recon.security.issuer=https://your-issuer.example \
  --recon.security.audience=reconledger
```

For an issuer that publishes rotating verification keys:

```bash
java -jar target/reconledger-0.1.0-SNAPSHOT-app.jar \
  --spring.profiles.active=postgres,secured \
  --recon.security.jwk-set-uri=https://issuer.example/.well-known/jwks.json \
  --recon.security.issuer=https://issuer.example \
  --recon.security.audience=reconledger
```

The two trust properties are mutually exclusive. A remote JWK Set URI must use
HTTPS; plain HTTP is accepted only for loopback verification. User information and
fragments are rejected. The URI is trusted operator configuration, not a request
value. RS256 remains fixed even if the published set advertises other algorithms.

Combine profiles as `postgres,secured` to use the [PostgreSQL configuration](postgresql.md).
Missing or ambiguous trust properties, a blank issuer/audience or an invalid pinned
key prevent this profile from starting. Omitting `secured` starts the unauthenticated demo: profile
selection is an operator responsibility, not an automatic network-safety check.
The original demo and restart scripts target that default mode. The separate
[secured JAR check](secured-package-checks.md) exercises actual profile startup,
retained tenant data and manual pinned-key replacement on restart.

Only RS256 signatures are accepted. Tokens must have a matching `iss`, an `aud`
containing the configured audience, an unexpired `exp`, and a nonblank `sub` of at
most 80 Java characters with no surrounding whitespace. The `tenant_id` must follow
the [tenant contract](tenants.md); old tokens without it now return 401. Spring also validates
`nbf` when present, with its default 60-second clock skew. Use stable opaque subject
IDs; a display name is not a durable identity. The configured issuer defines the
subject namespace and is limited to 2,048 characters to fit retained audit metadata.
New events retain both issuer and subject; earlier events remain unknown. Changing
issuers still requires an explicit tenant/identity migration, since tenant ownership
is not automatically mapped between providers. See [reviewer provenance](reviewer-provenance.md).

## Endpoint permissions

Scopes are space-separated values in `scope` (or Spring's supported `scp` claim).
They are independent: a review token does not implicitly gain read permission.

| Request | Required scope |
| --- | --- |
| GET batch list/detail, result detail, result events, `/api/review-session` | `recon:read` |
| POST `/api/batches` | `recon:write` |
| POST `/api/results/{id}/reviews` | `recon:review` |
| GET `/actuator/health` | None; no health details |
| GET the three review assets | Denied by default; public only with UI opt-in |
| Other routes or methods | Denied |

The [local review interface](review-interface.md) is denied by default, including
for valid API tokens. Explicitly enabling the [secured UI](secured-review-interface.md)
permits GET of its HTML, JavaScript and CSS only. Data requests still use bearer
authentication; no cookie login or external identity-provider login is added.

Send `Authorization: Bearer <token>`. Missing or invalid authentication returns 401
with a Bearer challenge; a valid token lacking the required scope returns 403.
Unavailable verification infrastructure (for example a failed or malformed JWKS
response) returns a fixed 503 problem body with no-store, no bearer challenge and no
retry delay. Remote error details are neither returned nor logged by that handler.
These filter-level errors are not the MVC `ProblemDetail` contract. Once authorized,
ordinary validation, body bounds, idempotency and optimistic locking still apply.
A request rejected at authentication/authorization cannot reserve a batch key or
change review state/history.

In secured mode omit `actor` from a review (JSON null is also accepted):

```json
{
  "targetState": "IN_REVIEW",
  "expectedVersion": 0,
  "note": "Checking the synthetic source file."
}
```

Supplying any non-null `actor` returns 400, even if it matches the token subject.
The default demo continues to require a nonblank caller-supplied actor. Existing
audit rows remain `LEGACY_UNKNOWN`; enabling this profile does not retroactively
authenticate them. New events distinguish `DEMO_LABEL` from `VERIFIED_JWT` and retain
the verified issuer for the latter. The actor string alone is still insufficient.

There is no session, Basic login or cookie/query-token authentication. CSRF protection
is disabled because authentication requires an explicit bearer header rather than
browser-supplied credentials. Adding a cookie-based UI login would require revisiting
that decision. HTTP endpoint checks are the access boundary; service/repository calls
inside the process are trusted and do not have a separate method-security layer.

## Verification and remaining work

Run `./mvnw verify` (or the PostgreSQL command in the linked guide).
`AuthenticatedApiTest` starts embedded Tomcat and signs short-lived synthetic tokens
with a fresh in-memory RSA private key. Only a temporary public PEM is written. It
uses real token decoding, HTTP and JDBC, with no identity provider or paid service.
The tests cover signature/algorithm, issuer/audience/time/subject rejection, every
data route, separate scopes, actor spoofing, unchanged state after rejection,
optimistic conflict, body bounds and session/token transport behavior. A separate
default-mode HTTP case retains the demo actor contract. See [observed results](verification.md).

The JWKS test publishes key A, replaces it with key B, and observes a second endpoint
fetch when a B token presents an unknown key ID. The removed A key, RS512, reserved
demo tenant and missing scope are rejected without reserving the tested idempotency
key. This is a loopback synthetic issuer, not evidence for an external provider,
DNS/TLS, cache expiry/durability, overlap timing or user revocation. Immediate
503/malformed/empty-set failures, cached-key continuity and recovery are now checked
separately with the same synthetic loopback endpoint.
See [the rotation boundary](jwks-rotation.md).

The profile does not supply TLS, user revocation, provider login, rate limits or tamper-resistant audit storage. A valid stolen bearer token
is usable until expiry or its verification key is no longer accepted; replacing the pinned public key requires restarting the app.
Manual pinned-key replacement on restart is covered by the [packaged check](secured-package-checks.md).
Provider integration, rotation operations and deployment controls need separate verification.

Tenant scoping trusts signed issuer membership; it does not validate an external
provider configuration or supply a membership administration system.

References: Spring Security [JWT resource servers](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
and [request authorization](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html).

Interview exercise: why does verifying a JWT's signature alone not establish its
intended API or grant access to a particular customer's batch?

## Optional local review screen

The secured profile remains API-only by default. `recon.security.review-ui-enabled=true`
permits the three static review assets; data routes still require bearer scopes and
tenant ownership. See [credential lifecycle and browser checks](secured-review-interface.md).
GET `/api/review-session` requires `recon:read` and returns verified display context
without creating a session or returning the token.

The JWKS mode has a dedicated [HTTP boundary](jwks-transport.md): a 64 KiB body
limit, identity encoding, no redirects, and two-second connect/read-inactivity
timeouts. This is not a total request deadline or a refresh-admission limit.

## Decoder capacity

The secured profile applies [JWT decoding admission](jwt-admission.md) before
parsing, key-cache waits and signature validation. The default maximum is 32
simultaneous decode calls per application decoder, configurable with
`recon.security.max-concurrent-decodes` (1..1000). Saturation returns sanitized
503/no-store; admitted invalid tokens still return 401. This shared allowance
is separate from the later MVC concurrency and rate budgets.
