# Request diagnostics

Every response that reaches the application filter carries a server-generated
`X-Request-ID`. The same opaque UUID
appears in one completion event after the request finishes:

```text
request_completed request_id=… method=GET route=/api/batches/{id} status=404 duration_ms=3 outcome=client_error
```

The filter runs before Spring Security, so successful requests, authentication and
scope rejections, MVC errors, health checks and unmapped requests receive an ID.
Client-supplied `X-Request-ID` values are ignored rather than trusted or echoed. A
fresh value is created at this service boundary.

## Deliberately small fields

The completion event contains only:

- the generated request ID;
- a known HTTP method, or `OTHER`;
- Spring's matched route template, such as `/api/results/{id}`, or `unmatched`;
- the observed HTTP status (or 500 for an escaped failure), elapsed filter lifetime
  in whole milliseconds and a status-class outcome.

It does not log the raw URL, query string, path variables, headers, bearer token,
tenant or subject, idempotency key, request/response body, exception message, source
records, amount, currency, review note or result identifiers. This is why a security
rejection or unknown route is labeled `unmatched`: the filter intentionally does not
fall back to the caller-controlled path.

The request ID is present in SLF4J's mapped diagnostic context while downstream code
runs and is removed (or the prior value restored) before the servlet thread can be
reused. The existing database-failure event includes this ID and the exception class,
but still omits the exception message, SQL and request data.

## Scope and limits

The ID correlates this process's response and logs. ReconLedger does not accept an
upstream trace ID, propagate the generated ID to another service, or implement a
distributed trace. UUID uniqueness is probabilistic; the ID is not an authorization
credential, idempotency key, tenant identifier or tamper-proof audit record.

The elapsed value covers the servlet-filter lifetime observed by this process. It is
not a client latency, database breakdown, service-level objective or load result.
Logging availability, transport, access control, rotation, retention, integrity and
redaction outside these application events remain deployment responsibilities. A
reverse proxy can remove or replace response headers and should have its own tested
correlation policy.

## Verification

`RequestDiagnosticsFilterTest` checks mapped-route logging, status/outcome fields,
server replacement of a supplied ID, MDC cleanup and exception handling. Its fixtures
place sensitive-looking values in the raw path, query, header and exception; none may
appear in the captured completion event. `AuthenticatedApiTest` sends real HTTP to
embedded Tomcat and checks distinct response IDs for 200, 401 and 404 paths through
the actual security and MVC chains. The packaged smoke workflow also requires a
canonical, unique response ID for every request it observes, matches each ID to one
completion event after restart, and checks that its synthetic idempotency key, batch
ID and result ID do not occur in those events.

Run the shared contract on both database engines with `./mvnw verify`; see
[the verification record](verification.md) for the observed run and environment.
