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
requires health behavior to remain otherwise unchanged. Secured embedded-Tomcat checks cover security
responses separately.

```sh
./mvnw verify
```

Interview exercise: why must this header be applied before the security chain if
401 and 403 bodies can also reveal API behavior, and what data can `no-store` never
remove from a client that has already received it?
