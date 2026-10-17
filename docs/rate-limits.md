# Optional API request budget

A configured token bucket limits how often requests enter the reconciliation and
review-session controllers in one application process. Requests that cannot acquire
a token receive 429 before argument conversion, JSON parsing or controller/database
work. The existing authentication, tenant checks and body bounds remain separate.

Enable it when starting the executable JAR:

```sh
java -jar target/reconledger-0.1.0-SNAPSHOT-app.jar \
  --recon.rate-limit.enabled=true \
  --recon.rate-limit.capacity=20 \
  --recon.rate-limit.refill-millis=100
```

It is disabled by default in both demo and secured profiles. When enabled, capacity
defaults to 20 requests and refill defaults to one token per 100 milliseconds
(10 tokens per second). These are demonstration settings, not measured capacity or
an operational recommendation. Capacity must be 1–10,000 and refill interval
1–60,000 milliseconds; invalid enabled configuration prevents startup.

## Admission and retries

All mapped reconciliation routes and `GET /api/review-session` share one bucket.
Each admitted request spends one token, including idempotent replays and requests
that later fail validation, ownership checks or database work. There is no refund.
A burst may consume the full capacity immediately; elapsed monotonic time restores
fractional credit up to capacity. Admission is synchronized, has no waiting queue
or background task, and keeps constant-sized state with no caller-ID map.

Spring Security runs before MVC. Missing/invalid credentials and insufficient
endpoint scopes therefore retain 401/403 and spend no tokens. Health, static review
assets and MVC error dispatches are outside the bucket. This limiter is not an
authentication control; future API controller classes must be explicitly included.

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

The single budget bounds admitted request rate without retaining attacker-selected
keys or guessing tenant/IP fairness. All tenants compete for it: a noisy tenant can
starve another, including read requests needed to recover from a failed write.
Restarting restores the initial burst; replicas each have their own budget. It is
not a distributed quota, per-tenant allowance, concurrent-request bound or DDoS
control. Authentication, sockets, headers, upload buffering, health/static requests
and rejected responses still cost resources. Slow requests can overlap indefinitely
within this rate policy. No throughput, latency, load or production-readiness claim
is made. An edge limit, tenant fairness and concurrency/upload controls remain work.

## Reproduction and evidence

```sh
./mvnw -Dtest=RequestRateBudgetTest,RateLimitHttpTest test
./mvnw verify
```

Five unit/configuration cases cover fractional refill, exact rounding/capacity,
12 simultaneous callers competing for three tokens, signed monotonic-clock wrap,
configuration bounds and disabled/enabled startup. Three secured embedded-Tomcat
HTTP cases use ephemeral synthetic JWTs and a controlled monotonic clock: malformed
requests/retries, unchanged review state/audit, all current data routes, shared
cross-tenant budget, security precedence and health/static access. Advancing this
clock avoids timing sleeps; it is not a real-time rate/load benchmark.

The existing suite and packaged checks exercise the default disabled mode. Hosted
Java/database verification for this increment will be recorded in the pull request;
local Maven currently lacks the cached Spring Boot parent POM.

Interview exercise: why is a rate budget insufficient to bound concurrent database
work, and what changes when the same configuration runs on four replicas?
