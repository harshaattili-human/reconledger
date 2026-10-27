package dev.harshaattili.reconledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.ResourceAccessException;

@org.junit.jupiter.api.Timeout(15)
class JwksHttpTest {
    private final AtomicReference<HttpHandler> handler = new AtomicReference<>();
    private final AtomicInteger redirects = new AtomicInteger();
    private final AtomicReference<String> encoding = new AtomicReference<>();
    private java.util.concurrent.ExecutorService threads;
    private HttpServer server;
    private URI uri;
    private org.springframework.web.client.RestOperations transport;

    @BeforeEach void start() throws Exception {
        threads = Executors.newCachedThreadPool();
        transport = JwksHttp.create();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(threads);
        server.createContext("/jwks", exchange -> {
            encoding.set(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
            try { handler.get().handle(exchange); } finally { exchange.close(); }
        });
        server.createContext("/redirected", exchange -> {
            redirects.incrementAndGet();
            send(exchange, "{\"keys\":[]}", false);
        });
        server.start();
        uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/jwks");
    }

    @AfterEach void stop() {
        server.stop(0);
        threads.shutdownNow();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void acceptsExactByteLimitWithFixedOrChunkedFraming(boolean chunked) {
        String prefix = "{\"keys\":[],\"note\":\"é\"}";
        String text = prefix + " ".repeat(JwksHttp.MAX_BYTES - prefix.getBytes(StandardCharsets.UTF_8).length);
        handler.set(exchange -> send(exchange, text, chunked));
        assertThat(fetch()).isEqualTo(text);
        assertThat(encoding.get()).isEqualTo("identity");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void rejectsExcessWithoutWaitingForTheRestOfTheBody(boolean chunked) throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        handler.set(exchange -> {
            exchange.sendResponseHeaders(200, chunked ? 0 : JwksHttp.MAX_BYTES + 1);
            if (chunked) {
                exchange.getResponseBody().write(new byte[JwksHttp.MAX_BYTES + 1]);
                exchange.getResponseBody().flush();
            } // Fixed length is rejected from headers before any body exists.
            held.countDown();
            await(release);
        });
        try {
            var result = threads.submit(this::fetch);
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                .hasRootCauseMessage("JWKS response is too large");
            assertThat(release.getCount()).isEqualTo(1); // Endpoint has not finished the response.
        } finally { release.countDown(); }
        recover();
    }

    @Test void rejectsPrematureEofEvenWhenThePartialBodyIsValidJson() throws Exception {
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            var sent = threads.submit(() -> {
                try (var client = socket.accept()) {
                    var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                        client.getInputStream(), StandardCharsets.US_ASCII));
                    for (String line; (line = reader.readLine()) != null && !line.isEmpty();) { }
                    client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 21\r\n"
                        + "Connection: close\r\n\r\n{\"keys\":[]}").getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().flush();
                }
                return null;
            });
            URI truncated = URI.create("http://127.0.0.1:" + socket.getLocalPort() + "/jwks");
            assertThatThrownBy(() -> JwksHttp.create().getForObject(truncated, String.class))
                .isInstanceOf(ResourceAccessException.class);
            sent.get(5, TimeUnit.SECONDS);
        }
        recover();
    }

    @ParameterizedTest @ValueSource(ints = {302, 307})
    void doesNotFollowRedirects(int status) {
        handler.set(exchange -> {
            exchange.getResponseHeaders().set("Location", "/redirected");
            exchange.sendResponseHeaders(status, -1);
        });
        assertThatThrownBy(this::fetch).isInstanceOf(ResourceAccessException.class)
            .hasRootCauseMessage("JWKS endpoint did not return 200");
        assertThat(redirects.get()).isZero();
        recover();
    }

    @Test void rejectsEncodedResponsesAndErrorBodiesBeforeParsing() {
        handler.set(exchange -> {
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            send(exchange, "synthetic-upstream-marker", false);
        });
        assertThatThrownBy(this::fetch).hasRootCauseMessage("JWKS content encoding is not supported");
        handler.set(exchange -> {
            exchange.sendResponseHeaders(503, JwksHttp.MAX_BYTES * 10L);
        });
        assertThatThrownBy(this::fetch).hasRootCauseMessage("JWKS endpoint did not return 200");
        recover();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void timesOutStalledHeadersOrBodyThenRecovers(boolean headersSent) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        handler.set(exchange -> {
            if (headersSent) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
            }
            entered.countDown();
            await(release);
        });
        try {
            var result = threads.submit(this::fetch);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> result.get(8, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(java.net.SocketTimeoutException.class);
            assertThat(release.getCount()).isEqualTo(1);
        } finally { release.countDown(); }
        recover();
    }

    @ParameterizedTest @ValueSource(strings = {"headers", "fixed", "chunked"})
    void continuousProgressCannotResetTheFetchDeadline(String phase) throws Exception {
        try (var listener = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            var entered = new CountDownLatch(1);
            var eof = new CountDownLatch(1);
            var finished = new CountDownLatch(1);
            var progress = new AtomicInteger();
            var serving = threads.submit(() -> {
                try (var peer = listener.accept()) {
                    peer.setSoTimeout(6_000);
                    readRequest(peer);
                    threads.submit(() -> {
                        try { if (peer.getInputStream().read() == -1) eof.countDown(); }
                        catch (java.io.IOException ignored) { }
                    });
                    String headers = phase.equals("headers") ? "HTTP/1.1 200 OK\r\nX-Slow: "
                        : "HTTP/1.1 200 OK\r\n" + (phase.equals("fixed")
                            ? "Content-Length: 10000\r\n\r\n" : "Transfer-Encoding: chunked\r\n\r\n");
                    peer.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                    for (int i = 0; i < 50; i++) {
                        String part = phase.equals("chunked") ? "1\r\n \r\n" : " ";
                        peer.getOutputStream().write(part.getBytes(StandardCharsets.US_ASCII));
                        peer.getOutputStream().flush();
                        progress.incrementAndGet();
                        entered.countDown();
                        if (eof.await(100, TimeUnit.MILLISECONDS)) break;
                    }
                } catch (java.io.IOException disconnected) {
                    // The independent reader must also observe EOF; write failure alone is insufficient.
                } finally { finished.countDown(); }
                return null;
            });
            URI slow = URI.create("http://127.0.0.1:" + listener.getLocalPort() + "/jwks");
            var result = threads.submit(() -> transport.getForObject(slow, String.class));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> result.get(4, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(java.net.SocketTimeoutException.class)
                .hasRootCauseMessage("JWKS fetch deadline exceeded");
            assertThat(progress.get()).isGreaterThan(2).isLessThan(50);
            assertThat(eof.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
            serving.get(1, TimeUnit.SECONDS);
        }
        recover(); // Same transport instance after cancellation.
    }

    @Test void callerInterruptionCancelsTheExchangeAndRestoresInterruptStatus() throws Exception {
        try (var listener = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            var entered = new CountDownLatch(1);
            var eof = new CountDownLatch(1);
            var serving = threads.submit(() -> {
                try (var peer = listener.accept()) {
                    peer.setSoTimeout(5_000);
                    readRequest(peer);
                    entered.countDown();
                    if (peer.getInputStream().read() == -1) eof.countDown();
                }
                return null;
            });
            var failure = new AtomicReference<Throwable>();
            var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
            URI stalled = URI.create("http://127.0.0.1:" + listener.getLocalPort() + "/jwks");
            Thread caller = new Thread(() -> {
                try { transport.getForObject(stalled, String.class); }
                catch (Throwable error) { failure.set(error); }
                finally { interrupted.set(Thread.currentThread().isInterrupted()); }
            });
            caller.start();
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                caller.interrupt();
                caller.join(1_000);
                assertThat(caller.isAlive()).isFalse();
                assertThat(failure.get()).isInstanceOf(ResourceAccessException.class)
                    .hasRootCauseInstanceOf(java.io.InterruptedIOException.class);
                assertThat(interrupted.get()).isTrue();
                assertThat(eof.await(2, TimeUnit.SECONDS)).isTrue();
                serving.get(1, TimeUnit.SECONDS);
            } finally { caller.interrupt(); caller.join(3_000); }
        }
        recover();
    }

    private static void readRequest(java.net.Socket peer) throws java.io.IOException {
        var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
            peer.getInputStream(), StandardCharsets.US_ASCII));
        for (String line; (line = reader.readLine()) != null && !line.isEmpty();) { }
    }

    private String fetch() { return transport.getForObject(uri, String.class); }
    private void recover() {
        handler.set(exchange -> send(exchange, "{\"keys\":[]}", false));
        assertThat(fetch()).isEqualTo("{\"keys\":[]}");
    }
    private static void send(HttpExchange exchange, String text, boolean chunked) throws java.io.IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, chunked ? 0 : bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
    private static void await(CountDownLatch release) {
        try { release.await(15, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
