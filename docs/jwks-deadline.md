# JWKS fetch completion deadline

An endpoint could keep the earlier two-second read-inactivity timeout alive by
sending occasional bytes. The dedicated JWKS transport now uses one 2,000 ms
monotonic budget from request construction/dispatch through complete body receipt.
Progress does not reset it. This is an operator policy constant, not a token claim
or requester-controlled timeout. The pinned PEM decoder does not fetch JWKS.

Java 17 `HttpClient.sendAsync` supplies a bounded `BodySubscriber<byte[]>`. Its
completion stage finishes only after the entire accepted body, rather than when
headers or an open stream are available. The synchronous Spring adapter waits on
that future for the remaining budget and rejects a result observed after the
budget. On timeout it cancels the future and throws a fixed timeout error. Caller
interruption also cancels, restores the thread's interrupt status and propagates
an I/O failure. Existing bearer failure handling maps infrastructure failures to
sanitized 503/no-store, without upstream body, URI or exception text.

The subscriber rejects non-200, encoded or declared oversized replies before
body consumption, checks each delivered buffer before copying and cancels when
the 65,536-byte cap would be exceeded. Spring receives only detached complete
bytes. Redirects are disabled, no cookies/authenticator are configured, and there
is no application retry. The JDK owns connection pooling and transport recovery;
this is not a guarantee of one wire connection attempt in every network failure.

## Scope and tradeoff

A legitimately slow key endpoint can now fail even while making progress. A
cached key may continue working; an uncached key may return 503. Connection setup
has the same two-second configured limit and shares the fetch budget.

This is a cooperative fetch deadline, not an entire authentication/request SLA.
Nimbus cache-lock waiting before the transport, key parsing, signature/claim
validation, servlet scheduling and response delivery are outside this transport
budget. Cancellation requests transport cleanup; it cannot stop DNS resolver
threads, remote work or arbitrary JVM pauses. Loopback EOF observations below
show cleanup for those fixtures, not every TLS/proxy failure. There is no new
concurrency admission, fairness, distributed budget or external issuer claim.

## Reproduce and observed evidence

```sh
./mvnw -Dtest=JwksHttpTest,JwksRotationHttpTest test
./mvnw verify
```

Three raw-socket cases continuously progress headers, a fixed-length body or a
chunked body every 100 ms. Each uses the stock budget, requires the deadline error
before the endpoint's 50-part sequence completes, observes peer EOF, then fetches
successfully through the same transport. A fourth case interrupts an actual
waiting caller, checks its interrupt flag, endpoint EOF and same-client recovery.
These tests use latches and generous outer guards, not a latency benchmark.

One additional embedded-Tomcat/RS256/JDBC workflow trickles a valid key set,
checks sanitized 503 and zero rows for the pending idempotency key, then serves
the complete set and checks create/replay with exactly one batch. The prior ten
transport cases and four decoder workflows continue to run. Package checks use
pinned keys, not this loopback JWKS endpoint.

Implementation `e00098d83b69ce854a6179c99670fc88a7b0e110` passed all five jobs in
[run 40574005513](https://github.com/harshaattili-human/reconledger/actions/runs/40574005513).
H2 job `122435460518` and PostgreSQL job `122435461100` each passed the same
111 cases with zero failures, errors or skips (not 222 distinct cases). This
includes 14 direct transport executions and five secured JWKS workflows. Browser
`122435461051` passed 30 existing Chromium executions; restore `122435460956`
passed the PostgreSQL rehearsal; audit `122435461028` inventoried 73 libraries
with zero advisory entries in that snapshot, not a vulnerability-free result.
Both database jobs also passed packaged default/secured restart, pinned-key
replacement and retained Boot 3 data upgrades.

Environment: Boot 4.0.8, H2 2.3.232, PostgreSQL 16.15, Ubuntu 24.04; H2 used Java
17.0.20.1 and PostgreSQL used 17.0.21. Local Maven offline execution could not
resolve the uncached Boot 4.0.8 parent; Java execution is hosted. Documentation
links and whitespace passed locally. The final documentation commit also removes
an unused test-duration construction seam; the stock policy remains 2,000 ms.

Primary references: Java 17 [HTTP client](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpClient.html)
and [body subscriber lifecycle](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpResponse.BodySubscriber.html).

Optional exercise: why does returning an InputStream at response headers make a
future timeout insufficient to bound full body receipt, and why can a Nimbus
cache-lock waiter still outlive a single fetch budget?
