# API response storage policy

Every response under the exact `/api` path or `/api/` prefix includes
`Cache-Control: no-store`. The policy covers successful reads and writes, validation
and not-found responses, and secured-profile 401/403 failures that stop before MVC.
It is installed in the highest-precedence request diagnostics filter so later
security and controller layers cannot accidentally omit the header.

The rule protects responses that can contain source records, discrepancy evidence,
review notes, reviewer provenance or tenant-specific metadata. It also keeps error
representations under the same simple contract. The actuator health endpoint,
packaged review assets and nearby `/apiary` path are not selected by this filter.
Spring Security may still add its existing cache-prevention headers to those
responses; this change neither removes nor makes claims about them.

`no-store` is an HTTP storage directive. It does not erase browser memory, control
screenshots, secure logs, encrypt transport, revoke tokens, or prevent an authorized
client from saving a response. TLS and client/device policy remain deployment work.
The loopback demo still has no authentication and must not hold real records.

Unit checks cover exact prefix matching and early header application. The shared
H2/PostgreSQL API suite requires the header on create, detail and 404 responses and
requires health behavior to remain otherwise unchanged. Secured embedded-Tomcat
checks cover security responses separately.

```sh
./mvnw verify
```

Interview exercise: why must this header be applied before the security chain if
401 and 403 bodies can also reveal API behavior, and what data can `no-store` never
remove from a client that has already received it?

## Verification — November 4, 2026

Implementation `820b717a608f6aabef94f8c8b818a17b791f70af` first ran in
[run 41853158367](https://github.com/harshaattili-human/reconledger/actions/runs/41853158367).
Both database jobs reached the 123-case suite and failed only two new assertions
that expected the health endpoint to have no cache header. Spring Security already
adds its own cache-prevention value there. The runtime API policy was unchanged;
the correction removed that assumption and documented the framework behavior.

Full remote-diff review then found that publishing the correction had truncated one
large test blob on the branch. Commit `b22e88717fbb9c0417f69bee3d4f6163710999f7`
restored the exact reviewed local blob before merge. Its comparison with `main`
contained nine intended files, 75 additions and one deletion.

[Run 41854740676](https://github.com/harshaattili-human/reconledger/actions/runs/41854740676)
then passed all five jobs. The H2 and PostgreSQL 16.15 jobs each passed the same 123
cases with zero failures, errors or skips; this is 123 cases on two engines, not 246
distinct cases. They also passed the existing default and secured executable-JAR
checks, restart, pinned-key and HTTPS JWKS rotation checks, and retained-data upgrade.
The PostgreSQL restore rehearsal passed separately.

The browser job passed 36 demo, 24 secured and ten repeated Chromium executions
across its existing three-engine matrix. The Java audit inventoried 73 packaged
libraries and reported zero advisory entries in its dated snapshot; this is not a
vulnerability-free claim. Hosted checks used Java 17.0.21, Spring Boot 4.0.8, H2
2.3.232 and PostgreSQL 16.15 on Ubuntu 24.04.

Local whitespace, diff and documentation checks passed. Maven execution was hosted
because the current workspace lacked the cached Spring Boot parent and could not
resolve Maven Central. No schema, tenant boundary, identity rule, review behavior or
synthetic dataset changed.

## Context-relative selection

The filter now selects `/api` relative to the servlet context, rather than matching
only the raw request URI. With `server.servlet.context-path=/ledger`, requests such
as `/ledger/api/batches` receive the same explicit policy. It also checks the
container-decoded servlet path, so an accepted encoded spelling such as `%61pi`
does not omit the header. The filter does not perform its own URL decoding or
change Spring Security's request matching/firewall rules. This covers the shipped
DispatcherServlet mapping; it is not a general custom servlet-mapping policy.

A unit case checks root/non-root contexts, early header application, decoded paths,
and an API-looking context whose health/assets must remain outside this rule.
Two secured embedded-Tomcat cases launch with `/ledger`, use ephemeral RSA tokens,
and check 200/201/400/401/403/404/409, replay, an encoded path and health. The default
root-context suite remains in place. These are synthetic HTTP contract checks;
the browser workspace and packaged scripts are not claimed context-path compatible.

Reproduce with `./mvnw -Dtest=RequestDiagnosticsFilterTest,ContextPathCachingHttpTest test`
on each documented database profile. Local offline Maven stopped at the uncached
Boot parent before compilation; hosted results are recorded in the pull request.
No schema, identity, tenant or reconciliation rule changes.

Reference: [Jakarta Servlet 6.1 request path elements](https://jakarta.ee/specifications/servlet/6.1/jakarta-servlet-spec-6.1.pdf), section 3.6.

Exercise: why can a raw-URI prefix check disagree with the route handled by the
application, and why should a response policy avoid inventing another URL decoder?
