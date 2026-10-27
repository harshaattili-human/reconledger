package dev.harshaattili.reconledger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.AbstractClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

/** A GET-only transport for the operator-configured JWKS endpoint. */
final class JwksHttp {
    static final int MAX_BYTES = 65_536;
    static final int TIMEOUT_MILLIS = 2_000;

    private JwksHttp() {}

    static RestOperations create() {
        Duration budget = Duration.ofMillis(TIMEOUT_MILLIS);
        long budgetNanos = budget.toNanos();
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(budget).build();
        return new RestTemplate((uri, method) -> {
            if (method != HttpMethod.GET) throw new IOException("JWKS transport requires GET");
            return new AbstractClientHttpRequest() {
                @Override public URI getURI() { return uri; }
                @Override public HttpMethod getMethod() { return method; }
                @Override protected OutputStream getBodyInternal(HttpHeaders headers) throws IOException {
                    throw new IOException("JWKS request bodies are not supported");
                }
                @Override protected ClientHttpResponse executeInternal(HttpHeaders headers) throws IOException {
                    return fetch(client, uri, budgetNanos);
                }
            };
        });
    }

    private static ClientHttpResponse fetch(HttpClient client, URI uri, long budgetNanos) throws IOException {
        long started = System.nanoTime();
        var request = HttpRequest.newBuilder(uri).GET()
            .header("Accept", "application/jwk-set+json, application/json")
            .header("Accept-Encoding", "identity").build();
        var pending = client.sendAsync(request, BoundedBody::new);
        byte[] bytes;
        try {
            long remaining = budgetNanos - (System.nanoTime() - started);
            if (remaining <= 0) throw new TimeoutException();
            bytes = pending.get(remaining, TimeUnit.NANOSECONDS).body();
            if (System.nanoTime() - started >= budgetNanos) throw new TimeoutException();
        } catch (TimeoutException timeout) {
            pending.cancel(true);
            throw new SocketTimeoutException("JWKS fetch deadline exceeded");
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("JWKS fetch interrupted");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof IOException io) throw io;
            throw new IOException("JWKS transport failed");
        }
        var responseHeaders = new HttpHeaders();
        responseHeaders.set(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8");
        responseHeaders.setContentLength(bytes.length);
        var body = new ByteArrayInputStream(bytes);
        return new ClientHttpResponse() {
            @Override public HttpStatusCode getStatusCode() { return HttpStatusCode.valueOf(200); }
            @Override public String getStatusText() { return "OK"; }
            @Override public HttpHeaders getHeaders() { return responseHeaders; }
            @Override public InputStream getBody() { return body; }
            @Override public void close() { /* Detached bounded bytes; no network body to drain. */ }
        };
    }

    /** Completion represents the entire body, not merely headers or an open InputStream. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final long declared;
        private final String rejection;
        private Flow.Subscription subscription;

        BoundedBody(HttpResponse.ResponseInfo response) {
            declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (response.statusCode() != 200) rejection = "JWKS endpoint did not return 200";
            else if (response.headers().allValues("Content-Encoding").stream()
                .anyMatch(value -> !value.equalsIgnoreCase("identity"))) {
                rejection = "JWKS content encoding is not supported";
            } else if (declared > MAX_BYTES) rejection = "JWKS response is too large";
            else rejection = null;
        }
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription incoming) {
            if (subscription != null) { incoming.cancel(); return; }
            subscription = incoming;
            if (rejection != null) reject(rejection);
            else incoming.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            for (var buffer : buffers) {
                // Reject before copying an oversized buffer; never accumulate more than MAX_BYTES.
                if (buffer.remaining() > MAX_BYTES - bytes.size()) {
                    reject("JWKS response is too large");
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { body.completeExceptionally(failure); }
        @Override public void onComplete() {
            if (declared >= 0 && declared != bytes.size()) reject("JWKS response ended early");
            else body.complete(bytes.toByteArray());
        }
        private void reject(String message) {
            subscription.cancel();
            body.completeExceptionally(new IOException(message));
        }
    }
}
