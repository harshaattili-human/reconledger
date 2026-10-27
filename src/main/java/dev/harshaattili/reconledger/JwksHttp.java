package dev.harshaattili.reconledger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
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
        return new RestTemplate((uri, method) -> {
            if (method != HttpMethod.GET) throw new IOException("JWKS transport requires GET");
            return new AbstractClientHttpRequest() {
                @Override public URI getURI() { return uri; }
                @Override public HttpMethod getMethod() { return method; }
                @Override protected OutputStream getBodyInternal(HttpHeaders headers) throws IOException {
                    throw new IOException("JWKS request bodies are not supported");
                }
                @Override protected ClientHttpResponse executeInternal(HttpHeaders headers) throws IOException {
                    return fetch(uri);
                }
            };
        });
    }

    private static ClientHttpResponse fetch(URI uri) throws IOException {
        var connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setRequestProperty("Accept", "application/jwk-set+json, application/json");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("Connection", "close");
        try {
            // Reject errors/redirects before reading their bodies. No upstream text enters an exception.
            if (connection.getResponseCode() != 200) throw new IOException("JWKS endpoint did not return 200");
            String encoding = connection.getHeaderField("Content-Encoding");
            if (encoding != null && !encoding.equalsIgnoreCase("identity")) {
                throw new IOException("JWKS content encoding is not supported");
            }
            long declared = connection.getContentLengthLong();
            if (declared > MAX_BYTES) throw new IOException("JWKS response is too large");
            // One lookahead byte detects excess even for unknown/chunked lengths. Never silently truncate.
            byte[] bytes = connection.getInputStream().readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("JWKS response is too large");
            if (declared >= 0 && bytes.length != declared) throw new IOException("JWKS response ended early");
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
        } finally {
            // Do not hand a live response to RestTemplate's response-close/drain path.
            connection.disconnect();
        }
    }
}
