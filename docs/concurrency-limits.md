# Concurrent API admission

The optional concurrency cap complements the request-rate budget: slowly completed
requests can overlap even at a low arrival rate. Enable it for the current
synchronous reconciliation and review-session controllers:

```sh
java -jar target/reconledger-0.1.0-SNAPSHOT-app.jar \
  --recon.concurrency-limit.enabled=true \
  --recon.concurrency-limit.capacity=8 \
  --recon.concurrency-limit.per-tenant-capacity=2
```

Both profiles default to disabled. Enabled capacity defaults to eight, accepts
1–1,000, and fails startup outside those bounds. Eight is an illustrative setting,
not measured service or database capacity. The optional per-tenant capacity defaults
to the global capacity for compatibility; set it lower to prevent a single tenant
from occupying every slot. It accepts 1 through the configured global capacity and
fails startup otherwise. In demo mode all callers share the single `demo` tenant,
so a lower per-tenant value becomes the effective demo limit.

## Tenant occupancy

Secured admission uses the existing verified `tenant_id`, never a request header,
body field, subject or client IP. Different subjects in one tenant share its ceiling;
the same subject in different authorized tenants uses their separate ceilings.
Missing verified context fails closed. Authentication and scope checks still happen
before this policy; it does not replace tenant authorization.

One short synchronized section checks and increments both the global count and the
tenant count. Neither changes on rejection. Completion uses the tenant captured at
admission, even if the thread's security context has since changed. Zero-count entries
are removed immediately: the map retains only active tenants and cannot contain more
entries than the global capacity. Rejected identities create no entries. There is no
historical tenant cache, idle eviction timer, waiting queue or background task.

This is an occupancy ceiling, not fair scheduling or reserved capacity. Several
tenants can collectively fill the global cap; a busy tenant can repeatedly reacquire
its own allowance. The independent rate bucket remains shared, so it can still
reject another tenant despite free concurrency slots. No per-tenant throughput or
latency guarantee is made. Each process/replica has independent counters.

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
the policy cannot predict when existing work will finish. Back off before a
retry; retain the creation key. An ambiguous review response still requires reading
current state/history before another submission. The UI never automatically retries
POST requests. Ordinary database-failure 503 responses retain their separate detail.

Health, review assets, error handlers and rejected authentication/scope requests
do not acquire slots. This does not reserve servlet threads for health or guarantee
health latency under a saturated server. Tenants compete within both ceilings;
replicas multiply them. Header parsing, authentication, socket buffers, rejected
responses, framework queues and work outside these controllers are not bounded.
The admitted body buffers are individually bounded, but this is not a measured
aggregate-memory ceiling. Slow uploads can hold all slots across enough tenants: upload/edge timeouts,
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

The original three unit/configuration cases exercise opt-in bounds, exact-once completion and
12 released-at-once callers competing for three retained slots. Two secured
embedded-Tomcat cases use actual JWT decoding and JDBC: a fixed-length and a chunked
partial upload occupy both slots, excess requests leave keys/review/audit unchanged,
health/assets/security precedence remain intact, and closing the two sockets allows
two more simultaneous uploads. Repeated parsing/body/ownership errors also recover
capacity. Existing rate HTTP cases enable a single concurrency slot to check release
after 429 and the interaction with token refill. These are synthetic correctness
fixtures, not throughput measurements. Packaged/browser workflows retain the default
disabled policy.

The tenant extension adds three unit cases: 12 simultaneous callers from two tenants
compete for three global/two per-tenant slots; 100 sequential tenant identities leave
no retained map entries; and missing secured context creates no state. Configuration
checks also cover the inherited default and invalid per-tenant values. Unit JWT contexts
are trusted fixtures, not signature-verification evidence.

The secured HTTP suite now configures two global/one per-tenant slots. One additional
case holds a fixed-length then a chunked upload for tenant A: another subject in A
is rejected without key/review/audit writes, while B can read and create a batch.
Forged tenant headers do not bypass the ceiling. Closing A's upload permits its
previously rejected key to succeed; simultaneous A/B uploads also reject tenant C
at the global cap. The existing error cases verify recovery with both tenants.
These checks use real JWT decoding, embedded Tomcat and JDBC on each database.

## Tenant-ceiling verification — October 19, 2026

Implementation `0493d3b4cb0a099ffa2b986153f35f75e9e53eec` passed all five jobs in
[run 39264322559](https://github.com/harshaattili-human/reconledger/actions/runs/39264322559).
H2 `118099815045` and PostgreSQL `118099815219` each passed the same 85 Java cases
with zero failures/errors/skips: four added cases, not eight distinct cases across
databases. Both jobs also passed default/secured packaged checks, process restarts,
pinned-key replacement and the Boot 3 retained-data upgrade rehearsal.
Browser `118099814914` passed the existing 12 demo + six secured Chromium executions;
restore `118099815307` passed the logical PostgreSQL rehearsal. The per-tenant policy
is enabled in embedded HTTP tests, not those packaged/browser workflows.

Audit `118099815018` inventoried 73 packaged libraries with zero advisory entries in
its snapshot; this is not a vulnerability-free claim. Java 17.0.20.1, Boot 4.0.8,
H2 2.3.232 and PostgreSQL 16.15 ran on hosted Ubuntu 24.04. Local whitespace and
documentation links passed; offline Maven lacked the Boot parent, so Java/database
execution was hosted. No UI, schema, query policy or deployment change was made.
Final-head verification is linked from [PR 23](https://github.com/harshaattili-human/reconledger/pull/23).

Interview exercise: with eight global slots and two per tenant, what happens with
one busy tenant versus five busy tenants, and why can a shared rate bucket still
reject a tenant that has an available concurrency slot?

## Global-cap verification — October 18, 2026

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
