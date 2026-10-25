package dev.harshaattili.reconledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

class RequestDiagnosticsFilterTest {
    @Test void returnsAnOpaqueIdAndLogsOnlyTheMappedRoute() throws Exception {
        try (var logs = new LogCapture()) {
            var request = new MockHttpServletRequest("GET", "/api/batches/private-batch-id");
            request.setQueryString("access_token=private-token");
            request.addHeader(RequestDiagnosticsFilter.HEADER, "caller-selected-id");
            var response = new MockHttpServletResponse();

            new RequestDiagnosticsFilter().doFilter(request, response, (incoming, outgoing) -> {
                assertThat(MDC.get(RequestDiagnosticsFilter.MDC_KEY))
                    .isEqualTo(response.getHeader(RequestDiagnosticsFilter.HEADER));
                request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/batches/{id}");
                response.setStatus(404);
            });

            String requestId = response.getHeader(RequestDiagnosticsFilter.HEADER);
            assertThat(requestId).matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
                .isNotEqualTo("caller-selected-id");
            assertThat(MDC.get(RequestDiagnosticsFilter.MDC_KEY)).isNull();
            assertThat(logs.appender.list).hasSize(1);
            assertThat(logs.appender.list.get(0).getFormattedMessage())
                .contains("request_id=" + requestId, "method=GET", "route=/api/batches/{id}",
                    "status=404", "outcome=client_error")
                .doesNotContain("private-batch-id", "private-token", "caller-selected-id");
        }
    }

    @Test void recordsEscapedFailuresAndRestoresAnExistingDiagnosticContext() {
        try (var logs = new LogCapture()) {
            var request = new MockHttpServletRequest("UNTRUSTED", "/private/path");
            var response = new MockHttpServletResponse();
            MDC.put(RequestDiagnosticsFilter.MDC_KEY, "outer-request");
            try {
                assertThatThrownBy(() -> new RequestDiagnosticsFilter().doFilter(request, response,
                    (incoming, outgoing) -> { throw new ServletException("private failure detail"); }))
                    .isInstanceOf(ServletException.class);
                assertThat(MDC.get(RequestDiagnosticsFilter.MDC_KEY)).isEqualTo("outer-request");
                assertThat(logs.appender.list).hasSize(1);
                assertThat(logs.appender.list.get(0).getFormattedMessage())
                    .contains("method=OTHER", "route=unmatched", "status=500", "outcome=exception")
                    .doesNotContain("/private/path", "private failure detail", "outer-request");
            } finally {
                MDC.remove(RequestDiagnosticsFilter.MDC_KEY);
            }
        }
    }

    private static final class LogCapture implements AutoCloseable {
        final Logger logger = (Logger) LoggerFactory.getLogger(RequestDiagnosticsFilter.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        LogCapture() {
            appender.start();
            logger.addAppender(appender);
        }

        @Override public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
