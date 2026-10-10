package dev.harshaattili.reconledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;

class RequestBodyLimitAdviceTest {
    private final RequestBodyLimitAdvice advice = new RequestBodyLimitAdvice();

    @Test void rejectsOversizedDeclaredLengthWithoutReadingTheBody() {
        var stream = new CountingStream();
        assertTooLarge(message(stream, RequestBodyLimitAdvice.MAX_BYTES + 1L));
        assertThat(stream.reads).isZero();
    }

    @Test void boundsReadsEvenWhenLengthIsUnknownOrUnderstated() {
        for (long length : new long[] {-1, 1}) {
            var stream = new CountingStream();
            assertTooLarge(message(stream, length));
            assertThat(stream.reads).isEqualTo(RequestBodyLimitAdvice.MAX_BYTES + 1);
        }
    }

    private void assertTooLarge(HttpInputMessage message) {
        assertThatThrownBy(() -> advice.beforeBodyRead(message, null, null, null))
            .isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    }

    private HttpInputMessage message(InputStream stream, long length) {
        var headers = new HttpHeaders();
        if (length >= 0) headers.setContentLength(length);
        return new HttpInputMessage() {
            @Override public InputStream getBody() { return stream; }
            @Override public HttpHeaders getHeaders() { return headers; }
        };
    }

    private static class CountingStream extends InputStream {
        int reads;
        @Override public int read() { reads++; return ' '; }
    }
}
