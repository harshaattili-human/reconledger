package dev.harshaattili.reconledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiConcurrencyLimit.Rejected.class)
    public ResponseEntity<ProblemDetail> concurrencyLimited(ApiConcurrencyLimit.Rejected exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .cacheControl(CacheControl.noStore())
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "API concurrency capacity exhausted. No operation was started."));
    }

    @ExceptionHandler(ApiRateLimit.Rejected.class)
    public ResponseEntity<ProblemDetail> rateLimited(ApiRateLimit.Rejected exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retrySeconds))
            .cacheControl(CacheControl.noStore())
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "API request budget exhausted. Wait before retrying; no operation was started."));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> api(ApiException exception) {
        return problem(exception.status(), exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException exception) {
        var errors = exception.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField() + ": " + e.getDefaultMessage()).sorted().distinct().toList();
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed.");
        detail.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> malformed(HttpMessageNotReadableException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Malformed request: check JSON fields, dates, numbers and enum values.");
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ProblemDetail> database(DataAccessException exception) {
        // Avoid logging request data or returning SQL/schema details to clients.
        LOG.error("database_operation_failed request_id={} exception_type={}",
            MDC.get(RequestDiagnosticsFilter.MDC_KEY), exception.getClass().getSimpleName());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Database operation could not be completed. Retry using the same idempotency key or fetch the latest review state.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> invalidParameter(MethodArgumentTypeMismatchException exception) {
        String expected = exception.getRequiredType() == java.time.LocalDate.class
            ? "an ISO date (yyyy-MM-dd)" : "an integer within its supported range";
        return problem(HttpStatus.BAD_REQUEST, "Query parameter '" + exception.getName() + "' must be " + expected + ".");
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
