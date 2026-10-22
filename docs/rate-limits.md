# Optional process and tenant request budgets

A configured token bucket limits how often requests enter the reconciliation and
review-session controllers in one application process. Requests that cannot acquire
a token receive 429 before argument conversion, JSON parsing or controller/database
work. The existing authentication, tenant checks and body bounds remain separate.

Enable it when starting the executable JAR:

```sh
java -jar target/reconledger-0.1.0-SNAPSHOT-app.jar \
  --recon.rate-limit.enabled=true \
  --recon.rate-limit.capacity=20 \
  --recon.rate-limit.refill-millis=100 \
  --recon.rate-limit.per-tenant-enabled=true \
  --recon.rate-limit.per-tenant-capacity=5 \
  --recon.rate-limit.per-tenant-refill-millis=100 \
  --recon.rate-limit.max-tracked-tenants=1000
```

The process budget is disabled by default in both demo and secured profiles. When enabled, capacity
defaults to 20 requests and refill defaults to one token per 100 milliseconds
(10 tokens per second). These are demonstration settings, not measured capacity or
an operational recommendation. Capacity must be 1–10,000 and refill interval
1–60,000 milliseconds; invalid enabled configuration prevents startup.

The tenant budget is a separate opt-in. Its capacity and refill interval default to
the process values, preserving the original behavior unless a lower tenant burst or
slower tenant refill is configured. Tenant capacity cannot exceed process capacity.
At most 1–10,000 tenant buckets can be retained; the default is 1,000. Demo callers
all use the `demo` tenant. Secured calls use the already-validated `tenant_id`, never
a subject, header, request body, client IP or raw token.

## Admission and retries

All mapped reconciliation routes and `GET /api/review-session` share the process bucket.
Each admitted request spends one token, including idempotent replays and requests
that later fail validation, ownership checks or database work. There is no refund.
A burst may consume the full capacity immediately; elapsed monotonic time restores
fractional credit up to capacity. Admission is synchronized; exhausted callers never wait for refill. No background
task is needed, and state is constant-sized with no caller-ID map.

Spring Security runs before MVC. Missing/invalid credentials and insufficient
endpoint scopes therefore retain 401/403 and spend no tokens. Health, static review
assets and MVC error dispatches are outside the bucket. This limiter is not an
authentication control; future API controller classes must be explicitly included.

A configured tenant bucket is checked first. A tenant rejection spends no process
token. If the tenant check succeeds but the process bucket rejects the request, the
tenant token is not refunded; this keeps admission conservative. Neither rejection
reaches parsing, controller or database work.

Tenant state is bounded. A fully refilled idle bucket is removed when a new tenant
arrives. A partially depleted bucket is never evicted to make room, because rotating
signed tenant IDs could otherwise reset burst credit. If the tracking bound is full,
a new tenant fails closed with the earliest whole-second delay until a retained
bucket fully refills. No token, subject or request data is stored in this map.

A rejection returns `application/problem+json`, `Cache-Control: no-store` and a
`Retry-After` integer rounded up to the seconds needed for the next token. Other
callers may consume that token first, so this header is a delay hint, not a reserved
slot. Rejected batch creation leaves its idempotency key free. A rejected review
leaves the result version and audit unchanged. Wait and preserve the same creation
key when retrying; after any ambiguous response, inspect state before resubmitting a
review. The review UI retains its existing explicit reload flow and never retries
POST requests automatically.

The status/header follow [RFC 6585 section 4](https://www.rfc-editor.org/rfc/rfc6585.html#section-4).
MVC [interception](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-servlet/handlermapping-interceptor.html)
occurs before argument conversion; authentication continues to use the security
filter chain.

## Scope and tradeoffs

The process budget bounds admitted request rate. A lower tenant budget prevents one
tenant from taking the whole initial burst and can constrain its refill share. It is
still not fair queuing or reserved capacity: all tenants compete for the process
bucket, and timing can let one tenant consume process refills before another arrives.
The bounded tenant map can also reject a new tenant while all tracked buckets remain
depleted. The issuer remains responsible for tenant membership and identifier lifecycle.
Restarting restores the initial burst; replicas each have their own budget. It is
not a distributed quota, guaranteed per-tenant allowance, concurrent-request bound or DDoS
control. Authentication, sockets, headers, upload buffering, health/static requests
and rejected responses still cost resources. Slow requests can overlap indefinitely
within this rate policy. No throughput, latency, load or production-readiness claim
is made. An edge limit, fair scheduling and upload controls remain work.

## Reproduction and evidence

```sh
./mvnw -Dtest=RequestRateBudgetTest,TenantRequestRateBudgetTest,RateLimitHttpTest test
./mvnw verify
```

The original five unit/configuration cases cover fractional refill, exact rounding/capacity,
12 simultaneous callers competing for three tokens, signed monotonic-clock wrap,
configuration bounds and disabled/enabled startup. Three secured embedded-Tomcat
HTTP cases use ephemeral synthetic JWTs and a controlled monotonic clock: malformed
requests/retries, unchanged review state/audit, all current data routes, hierarchical
tenant/process budgets, security precedence and health/static access. Advancing this
clock avoids timing sleeps; it is not a real-time rate/load benchmark.

Three tenant-budget unit/configuration cases cover isolated and concurrent bursts,
bounded tracking, no depleted-bucket eviction, full-refill reclamation and startup
validation. The secured embedded-Tomcat cases now enable both levels: two tenants
receive separate bursts while all tenants remain subject to one process budget.
Security/health/static precedence, malformed input, idempotent replay and unchanged
review state remain covered. These are deterministic correctness fixtures, not a
traffic or fairness benchmark.

## Tenant-budget verification — October 22, 2026

Initial [run 39789181778](https://github.com/harshaattili-human/reconledger/actions/runs/39789181778)
compiled the implementation and ran the suite, but H2 and PostgreSQL each failed the
same HTTP assertion. The fixture spent both tenant tokens, advanced its controlled
clock by one 60-second refill interval, then incorrectly expected two immediately
available requests. Runtime admission behaved as configured: the second request
received 429. Commit `fa3a5b401ab4d29bc3c3015de3aff5df46baacfa` advances two intervals
before asserting the restored two-token burst; no policy or production code changed.

Final [run 39789497850](https://github.com/harshaattili-human/reconledger/actions/runs/39789497850)
passed all five jobs. H2 job `119861416553` and PostgreSQL job `119861417509`
each passed the same 88 Java cases with zero failures/errors/skips: three added cases,
not six distinct cases across databases. Both also passed the default and secured
executable-JAR checks, process restarts, pinned-key replacement and retained-data
upgrade from the trusted Boot 3 checkpoint. Browser job `119861416540` passed the
existing 12 demo and six secured Chromium executions. Restore job `119861416137`
passed the quiesced PostgreSQL logical-backup rehearsal.

Audit job `119861416509` inventoried 73 packaged libraries with zero advisory entries
in that scanner snapshot; this is not a vulnerability-free claim. Java 17.0.20.1,
Boot 4.0.8, H2 2.3.232, PostgreSQL 16.15 and Chromium 151.0.7922.34 ran on hosted
Ubuntu 24.04. Local whitespace, Python syntax and documentation checks passed;
Maven could not resolve the uncached Boot parent locally, so Java/database execution
is hosted evidence. The enabled tenant policy is exercised by embedded HTTP tests;
packaged and browser workflows retain the disabled default.

The existing suite and packaged checks exercise the default disabled mode. [Run 39011501728](https://github.com/harshaattili-human/reconledger/actions/runs/39011501728)
at implementation `9d3c1d0c43b433c81af24a918cd7689c87fb04fb` passed the same
76 Java cases on H2 and PostgreSQL, with zero failures/errors/skips. Jobs:
H2 `117334809993`, PostgreSQL `117334810113`, browser `117334810162`, restore
`117334810180`. The default/secured packaged workflows, process restarts, pinned-key
replacement, PostgreSQL restore and 12 demo + six secured Chromium executions also
passed. Those packaged/browser workflows use the default disabled rate policy;
the enabled policy is exercised by the embedded-Tomcat cases above.

Local whitespace/documentation links passed; Maven lacked the cached Spring Boot
parent POM, so compilation and Java/database/browser execution were hosted. A final
concurrency-test refinement waits for all 12 callers before releasing admission;
its exact-head CI result is retained in [PR 20](https://github.com/harshaattili-human/reconledger/pull/20).

Interview exercise: why should a tenant rejection happen before the process bucket,
why is a depleted tenant bucket not evicted under tracking pressure, and what fairness
guarantee is still missing?
