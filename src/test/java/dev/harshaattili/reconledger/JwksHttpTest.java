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

class JwksHttpTest {
    private final AtomicReference<HttpHandler> handler = new AtomicReference<>();
    private final AtomicInteger redirects = new AtomicInteger();
    private final AtomicReference<String> encoding = new AtomicReference<>();
    private java.util.concurrent.ExecutorService threads;
    private HttpServer server;
    private URI uri;

    @BeforeEach void start() throws Exception {
        threads = Executors.newCachedThreadPool();
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
        String text = "{\"keys\":[]}" + " ".repeat(JwksHttp.MAX_BYTES - 11);
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

    private String fetch() { return JwksHttp.create().getForObject(uri, String.class); }
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
