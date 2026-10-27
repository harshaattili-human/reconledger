# JWKS transport boundary

The optional JWKS decoder uses a dedicated GET-only transport. A trusted operator
still chooses the URI; token claims cannot select an endpoint. The pinned PEM
mode does not use this transport.

Each fetch requests identity encoding, disables redirects and URLConnection
caching, and accepts only HTTP 200 with absent or identity Content-Encoding.
Non-200 and encoded responses are rejected before reading their bodies. A declared
length over 65,536 bytes is rejected from headers. Otherwise, at most 65,537 bytes
are read: the last byte detects excess, including chunked responses. Excess is
rejected, never truncated. Only a complete accepted byte array is passed to Spring
and Nimbus for JSON/JWK parsing. The connection is disconnected in `finally`;
Spring receives a detached bounded response, so its response cleanup cannot drain
an unbounded network body. Connections are requested closed instead of pooled.

The connect and read-inactivity timeouts are each 2,000 ms. This is not a total
fetch deadline: continuous progress, DNS resolution, scheduling, TLS/transport
implementation details and parsing have separate behavior. The byte cap bounds
application body accumulation plus one lookahead byte, not headers, socket buffers,
copies, all simultaneous requests or total process memory. There is no fetch
admission limit, fair queue, circuit breaker or new retry policy. Nimbus retains
its own cache/refresh behavior; this change does not establish a cache-expiry or
revocation guarantee. Large legitimate key sets require a reviewed policy change.

Failures use the existing sanitized 503/no-store bearer failure response and
cannot dispatch an application write. No upstream body or exception message is
returned. Valid tokens using an already cached key may still work during an outage;
see [the cache/outage checks](jwks-rotation.md).

## Reproduce

```bash
./mvnw -Dtest=JwksHttpTest,JwksRotationHttpTest test
./mvnw verify
```

Nine new direct transport executions use real loopback HTTP: exact-cap fixed and
chunked responses; declared excess with no body and chunked excess held open;
302/307 with a target counter proving no follow; encoding/error-body rejection;
and stalled headers/body with the stock read timeout followed by recovery. The
server remains latched while the client must finish, detecting response cleanup
that waits for the rest of a rejected reply. These are controlled protocol
fixtures, not throughput or upstream-service measurements.

One additional secured embedded-Tomcat/JWT/JDBC workflow presents an oversized
otherwise valid JWKS, verifies 503 and no idempotency-row mutation, replaces it
with a valid key set, then verifies create/replay and exactly one batch. Existing
rotation, malformed/empty-set, cached-key, scope and tenant checks also run.

Implementation validation is pending hosted CI. Local Maven offline execution
could not resolve the uncached Spring Boot 4.0.8 parent; `git diff --check` passed.
Packaged checks still use pinned keys. These loopback checks do not establish an
external issuer, HTTPS certificate/proxy behavior, cache expiry, total deadline,
connect-timeout behavior against a blackholed network or user-level revocation.

The adapter follows the Spring [request abstraction](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/http/client/AbstractClientHttpRequest.html)
and Java 17 [connection API](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/HttpURLConnection.html).

Optional exercise: why can a response byte cap and a read-inactivity timeout both
hold while the total request still takes much longer than two seconds?
