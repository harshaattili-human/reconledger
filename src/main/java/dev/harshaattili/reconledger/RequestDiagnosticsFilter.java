package dev.harshaattili.reconledger;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/** Adds an opaque correlation ID and records one deliberately sparse completion event. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
final class RequestDiagnosticsFilter extends OncePerRequestFilter {
    static final String HEADER = "X-Request-ID";
    static final String MDC_KEY = "request_id";
    private static final Logger LOG = LoggerFactory.getLogger(RequestDiagnosticsFilter.class);
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        String previousRequestId = MDC.get(MDC_KEY);
        long started = System.nanoTime();
        Throwable failure = null;
        response.setHeader(HEADER, requestId);
        MDC.put(MDC_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            int status = failure == null ? response.getStatus() : 500;
            long durationMillis = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            LOG.info("request_completed request_id={} method={} route={} status={} duration_ms={} outcome={}",
                requestId, safeMethod(request.getMethod()), route(request), status, durationMillis,
                failure == null ? outcome(status) : "exception");
            if (previousRequestId == null) MDC.remove(MDC_KEY);
            else MDC.put(MDC_KEY, previousRequestId);
        }
    }

    private static String safeMethod(String method) {
        return METHODS.contains(method) ? method : "OTHER";
    }

    private static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof String value && !value.isBlank() ? value : "unmatched";
    }

    private static String outcome(int status) {
        if (status < 400) return "success";
        if (status < 500) return "client_error";
        return "server_error";
    }
}
