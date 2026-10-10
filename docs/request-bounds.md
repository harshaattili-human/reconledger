# Bound JSON before parsing

The record-count and field-length annotations run after deserialization. They do
not prevent a large array, string or trailing whitespace from entering the parser.
Tomcat's [maxPostSize](https://tomcat.apache.org/tomcat-10.1-doc/config/http.html)
applies to form parameter parsing, not a general JSON body limit.

`RequestBodyLimitAdvice` runs before the message converter for request bodies in
`ReconciliationController`. It rejects a declared length above 512 KiB, otherwise
reads at most 524,289 bytes and rejects if the extra byte exists. Jackson receives
the bounded byte array only after the entire body fits. This catches unknown-length
chunked bodies and large tails after a valid JSON object, even if a parser would
stop at that object's closing brace. It does not trust Content-Length alone.

The bound is fixed and shared by batch and review submissions. It accommodates
the supported 1,000-record fixture with 80-character identifiers, boundary amounts
and ordinary pretty-printing. A separate review limit could be added if a measured
need justifies another contract. Compressed bodies are explicitly unsupported;
there is no decompression stage with a different expansion limit.

## Checks

Run `./mvnw verify` on H2 or the [PostgreSQL test profile](postgresql.md), then
`python3 scripts/smoke.py` (add `--database postgres` for that profile).

Five tests use Java's HTTP/1.1 client against embedded Tomcat on a random loopback
port. They cover both known-length and unknown-length publishers, the exact byte
boundary, one-byte overflow with trailing whitespace, retry after rejection, review
state/audit preservation, UTF-8 byte counting, gzip rejection, malformed/empty JSON,
the maximum supported record fixture and the existing record-count validation.
Two focused stream tests check early rejection and bounded reads when the declared
length is absent or understated. The latter is an input-message unit test, not a
claim about accepting inconsistent HTTP framing from Tomcat.

The packaged smoke runner also sends fixed-length and chunked overflows, then
retries the unreserved idempotency key with ordinary input. Hosted results will be
recorded in [verification](verification.md) after execution.

## Limits and tradeoff

Buffering simplifies the pre-parse guarantee but adds a bounded copy per active
request. The 512 KiB number is not a cap on heap allocation, parsed object size,
container buffers or concurrent requests. A slow sender can still occupy a worker
while its body is read. Rate limits, connection/read deadlines and ingress controls
remain separate work, as do authentication and tenant authorization. The service
remains a loopback-only synthetic prototype.

This advice covers mapped JSON body conversion. It is not a server-wide policy for
unknown paths, other content types or any future streaming/multipart endpoint.
Revisit that scope when adding new controllers or ingress infrastructure.

Interview exercise: why is checking Content-Length insufficient for chunked uploads,
and why does a per-request byte limit not bound memory across concurrent requests?
