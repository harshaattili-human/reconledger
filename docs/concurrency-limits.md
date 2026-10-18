# Concurrent API admission

The optional concurrency cap complements the request-rate budget: slowly completed
requests can overlap even at a low arrival rate. Enable it for the current
synchronous reconciliation and review-session controllers:

```sh
java -jar target/reconledger-0.1.0-SNAPSHOT-app.jar \
  --recon.concurrency-limit.enabled=true \
  --recon.concurrency-limit.capacity=8
```

Both profiles default to disabled. Enabled capacity defaults to eight, accepts
1–1,000, and fails startup outside those bounds. Eight is an illustrative setting,
not measured service or database capacity. There is one semaphore per application
process, no caller map, waiting queue, background task or per-tenant guarantee.

## Lifecycle and response

Spring Security still checks credentials and scopes before MVC. The interceptor
selects actual `ReconciliationController` and `ReviewSessionController` handlers,
then attempts admission without waiting. It runs before the rate interceptor, JSON
body reads, conversion, controller calls and JDBC. A request holds its slot through
synchronous response serialization. `afterCompletion` returns it on success or
failure, including a later rate rejection, malformed body or disconnected upload.
The request marker prevents duplicate completion from returning a second slot.

Saturation returns 503 problem details with `Cache-Control: no-store` and detail
`API concurrency capacity exhausted. No operation was started.` No key, version or
audit write is attempted, and no rate token is charged. There is no `Retry-After`:
the semaphore cannot predict when existing work will finish. Back off before a
retry; retain the creation key. An ambiguous review response still requires reading
current state/history before another submission. The UI never automatically retries
POST requests. Ordinary database-failure 503 responses retain their separate detail.

Health, review assets, error handlers and rejected authentication/scope requests
do not acquire slots. This does not reserve servlet threads for health or guarantee
health latency under a saturated server. All tenants compete for the same cap;
replicas multiply it. Header parsing, authentication, socket buffers, rejected
responses, framework queues and work outside these controllers are not bounded.
The admitted body buffers are individually bounded, but this is not a measured
aggregate-memory ceiling. Slow uploads can hold all slots: upload/edge timeouts,
tenant fairness and operational limits are still needed. Disconnect during a JDBC
operation does not cancel or roll back that operation merely by releasing a socket.

All selected handlers currently return synchronous values. Before adding async
servlet handlers, streaming responses or background work, redesign and test their
lease lifecycle; ordinary interceptor completion is not sufficient for every async
timeout/dispatch path. Newly added controller classes require explicit inclusion.
This is an admission control, not an authorization layer or production-load claim.

## Reproduce

```sh
./mvnw -Dtest=ConcurrencyAdmissionTest,ConcurrencyHttpTest,RateLimitHttpTest test
./mvnw verify
```

Three unit/configuration cases exercise opt-in bounds, exact-once completion and
12 released-at-once callers competing for three retained slots. Two secured
embedded-Tomcat cases use actual JWT decoding and JDBC: a fixed-length and a chunked
partial upload occupy both slots, excess requests leave keys/review/audit unchanged,
health/assets/security precedence remain intact, and closing the two sockets allows
two more simultaneous uploads. Repeated parsing/body/ownership errors also recover
capacity. Existing rate HTTP cases enable a single concurrency slot to check release
after 429 and the interaction with token refill. These are synthetic correctness
fixtures, not throughput measurements. Packaged/browser workflows retain the default
disabled policy.

## Observed execution — October 18, 2026

Implementation `c1c14ef60eb7a276757d3e655bf8a17f3deb569b` passed all five jobs in
[run 39134194395](https://github.com/harshaattili-human/reconledger/actions/runs/39134194395).
H2 job `117695256897` and PostgreSQL job `117695256900` each passed the same 81 Java
cases with zero failures/errors/skips, including the five new cases above and the
three existing rate cases with concurrency enabled. Default/secured packaged
workflows, process restarts, pinned-key replacement and both Boot 3 retained-data
upgrade rehearsals also passed. Browser job `117695256896` passed 12 demo and six
secured Chromium executions; restore job `117695256782` passed. These existing
packaged/browser workflows keep admission disabled.

Java 17.0.20.1, Boot 4.0.8, H2 2.3.232 and PostgreSQL 16.15 ran on hosted Ubuntu
24.04. Audit job `117695256620` inventoried 73 shipped libraries with zero entries
in its advisory snapshot; this is not an absence-of-vulnerabilities claim. Local
whitespace checks passed, but Maven could not resolve the uncached parent POM
offline; no local Java pass is claimed. Final-head CI is retained in
[PR 22](https://github.com/harshaattili-human/reconledger/pull/22).

Reference: Spring's [interceptor lifecycle](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/HandlerInterceptor.html).

Interview exercise: why should a concurrency slot survive a database exception but
not survive completion of its error response, and why is a rate token not refunded?
