# JWKS transport boundary

The optional JWKS decoder uses a dedicated GET-only transport. A trusted operator
still chooses the URI; token claims cannot select an endpoint. The pinned PEM
mode does not use this transport.

Each fetch requests identity encoding, disables redirects and accepts only HTTP 200
with absent or identity Content-Encoding. Non-200, encoded and declared oversized
responses cancel their body subscription without consuming that body. The bounded
subscriber counts buffers before copying and accumulates at most 65,536 bytes;
excess is rejected, never truncated. Premature EOF also rejects. Only a complete
accepted byte array is passed to Spring and Nimbus for JSON/JWK parsing. Spring
receives a detached response, so its cleanup cannot drain a live network body.

The [fetch deadline](jwks-deadline.md) now replaces the earlier read-inactivity
policy. A shared Java HTTP client can reuse completed connections; cancellation
may close an incomplete exchange. The byte cap bounds application accumulation,
not delivered transport buffers, parser copies, headers or total process memory.
Nimbus retains its own cache/refresh behavior. There is no fetch admission limit,
fair queue, circuit breaker, application retry or revocation guarantee. Large
legitimate key sets require a reviewed policy change.

Failures use the existing sanitized 503/no-store bearer failure response and
cannot dispatch an application write. No upstream body or exception message is
returned. Valid tokens using an already cached key may still work during an outage;
see [the cache/outage checks](jwks-rotation.md).

## Reproduce

```bash
./mvnw -Dtest=JwksHttpTest,JwksRotationHttpTest test
./mvnw verify
```

The initial ten direct transport executions use real loopback HTTP: exact-cap fixed and
chunked responses; declared excess with no body and chunked excess held open;
premature EOF containing valid partial JSON; 302/307 with a target counter proving
no follow; encoding/error-body rejection;
and stalled headers/body with the then-current read timeout followed by recovery. The
server remains latched while the client must finish, detecting response cleanup
that waits for the rest of a rejected reply. These are controlled protocol
fixtures, not throughput or upstream-service measurements.

One additional secured embedded-Tomcat/JWT/JDBC workflow presents an oversized
otherwise valid JWKS, verifies 503 and no idempotency-row mutation, replaces it
with a valid key set, then verifies create/replay and exactly one batch. Existing
rotation, malformed/empty-set, cached-key, scope and tenant checks also run.

## Earlier transport verification — October 27, 2026

Implementation `95c07d79037c84fdb49e7b9df162985f37064df9` passed all five jobs in
[run 40532666233](https://github.com/harshaattili-human/reconledger/actions/runs/40532666233).
H2 `122289404617` and PostgreSQL `122289404561` each passed the same 106 cases with
zero failures, errors or skips. These are 106 cases on two engines, not 212 distinct
cases. Ten direct transport executions and the additional secured oversized-set
workflow account for 11 added cases. Both declared and chunked excess returned
while the server remained latched, and both stalled-response cases timed out before
the fixture released its response. The raw-socket truncated response was rejected;
exact-cap UTF-8 responses were preserved.

The earlier implementation run `40532245969` passed its 105-case suites, packaged
workflows, restore and audit. Its browser job was still installing system packages
when the truncated-response refinement was published. No runtime/test failure was
observed in that run; the complete run above is the acceptance evidence.

Browser `122289404654` passed 12 demo, eight secured and ten repeated Chromium
executions. Both database jobs passed existing default/secured JAR checks, restart,
pinned-key replacement and retained Boot 3 data upgrades. Restore `122289404367`
passed the existing PostgreSQL rehearsal. Audit `122289404501` inventoried 73
packaged libraries and zero advisory entries in its dated snapshot; this is not a
vulnerability-free claim.

Execution used Boot 4.0.8, H2 2.3.232, PostgreSQL 16.15 and Ubuntu 24.04. Database
and browser jobs resolved Java 17.0.20.1; the audit job resolved 17.0.21. Local
Maven offline execution could not resolve the uncached Spring Boot 4.0.8 parent;
relative documentation links and `git diff --check` passed.
Packaged checks still use pinned keys. These loopback checks do not establish an
external issuer, HTTPS certificate/proxy behavior, cache expiry, total deadline,
connect-timeout behavior against a blackholed network or user-level revocation.

The original adapter followed the Spring [request abstraction](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/http/client/AbstractClientHttpRequest.html)
and Java 17 [connection API](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/HttpURLConnection.html).

Optional exercise: why can a response byte cap and a read-inactivity timeout both
hold while the total request still takes much longer than two seconds?
