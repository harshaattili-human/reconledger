# Authenticated reviews

The default loopback demo accepts a caller-supplied `actor`. The optional `secured`
profile verifies bearer JWTs and records the verified `sub` as the reviewer instead.
It separates reading evidence, creating batches and writing review decisions.

Each token must also carry a valid signed `tenant_id`; data access and idempotency
keys are scoped to that tenant. See [tenant ownership and migration](tenants.md).
The issuer must establish membership before signing this claim. Do not expose the
prototype publicly or load real customer data.

## Start the profile

Supply an X.509 PEM RSA public key (at least 2048 bits), exact issuer and intended
audience. The service holds only the public key; it does not issue tokens or store
a signing key. Use a token signed by the corresponding trusted issuer.

```bash
java -jar target/reconledger-0.1.0-SNAPSHOT-app.jar \
  --spring.profiles.active=secured \
  --recon.security.public-key=file:/absolute/path/issuer-public.pem \
  --recon.security.issuer=https://your-issuer.example \
  --recon.security.audience=reconledger
```

Combine profiles as `postgres,secured` to use the [PostgreSQL configuration](postgresql.md).
Missing trust properties, a blank issuer/audience or an invalid key prevent this
profile from starting. Omitting `secured` starts the unauthenticated demo: profile
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
subject namespace. Changing issuers against an existing database requires an
explicit identity migration, since audit rows currently store only the subject.

## Endpoint permissions

Scopes are space-separated values in `scope` (or Spring's supported `scp` claim).
They are independent: a review token does not implicitly gain read permission.

| Request | Required scope |
| --- | --- |
| GET batch list/detail, result detail, result events | `recon:read` |
| POST `/api/batches` | `recon:write` |
| POST `/api/results/{id}/reviews` | `recon:review` |
| GET `/actuator/health` | None; no health details |
| Other routes or methods | Denied |

Send `Authorization: Bearer <token>`. Missing or invalid authentication returns 401
with a Bearer challenge; a valid token lacking the required scope returns 403.
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
demo audit rows remain unverified labels; enabling this profile does not retroactively
authenticate them. Both modes currently use the same audit schema, so the actor field
alone does not distinguish historical demo rows from authenticated reviews.

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

The profile does not supply TLS, automatic key rotation/JWKS, revocation, rate limits or tamper-resistant audit storage. A valid stolen bearer token
is usable until expiry; replacing the pinned public key requires restarting the app.
Provider integration, key rotation and deployment controls need separate verification.

Tenant scoping trusts signed issuer membership; it does not validate an external
provider configuration or supply a membership administration system.

References: Spring Security 6.5 [JWT resource servers](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html)
and [request authorization](https://docs.spring.io/spring-security/reference/6.5/servlet/authorization/authorize-http-requests.html).

Interview exercise: why does verifying a JWT's signature alone not establish its
intended API or grant access to a particular customer's batch?
