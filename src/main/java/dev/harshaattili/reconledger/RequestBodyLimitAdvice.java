package dev.harshaattili.reconledger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** Bound the complete JSON body before Jackson allocates its object graph. */
@ControllerAdvice(assignableTypes = ReconciliationController.class)
public class RequestBodyLimitAdvice extends RequestBodyAdviceAdapter {
    static final int MAX_BYTES = 512 * 1024;

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter,
            Type targetType, Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        var encodings = input.getHeaders().get(HttpHeaders.CONTENT_ENCODING);
        if (encodings != null && encodings.stream().anyMatch(value -> !value.equalsIgnoreCase("identity"))) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content-Encoding must be absent or identity.");
        }
        if (input.getHeaders().getContentLength() > MAX_BYTES) throw tooLarge();

        // Read one extra byte to detect overflow, including chunked bodies and JSON padding.
        byte[] body = input.getBody().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) throw tooLarge();
        var bounded = new ByteArrayInputStream(body);
        return new HttpInputMessage() {
            @Override public InputStream getBody() { return bounded; }
            @Override public HttpHeaders getHeaders() { return input.getHeaders(); }
        };
    }

    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
            "Request body exceeds the " + MAX_BYTES + " byte limit.");
    }
}
