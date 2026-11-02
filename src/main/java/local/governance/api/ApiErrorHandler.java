package local.governance.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<FailureBody> handle(ApiException exception, HttpServletRequest request) {
        UUID correlationId = correlationId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
        headers.set("X-Correlation-Id", correlationId.toString());
        if (exception.retryAfterSeconds() != null) {
            headers.set(HttpHeaders.RETRY_AFTER, exception.retryAfterSeconds().toString());
        }
        FailureBody body = new FailureBody(
                exception.code(),
                exception.getMessage(),
                correlationId,
                exception.retryAfterSeconds(),
                exception.fieldErrors().isEmpty() ? null : exception.fieldErrors());
        return new ResponseEntity<>(body, headers, exception.status());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<FailureBody> malformed(HttpServletRequest request) {
        return handle(new ApiException("REQUEST_INVALID", "Check the request and try again."), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<FailureBody> unsupportedMediaType(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
        return new ResponseEntity<>(
                new FailureBody("REQUEST_INVALID", "Use application/json.", correlationId(request), null, null),
                headers,
                415);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Void> methodNotAllowed() {
        return ResponseEntity.status(405).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<FailureBody> missingRoute(HttpServletRequest request) {
        return handle(new ApiException("NOT_FOUND", "We couldn't find that item."), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<FailureBody> unexpected(Exception exception, HttpServletRequest request) {
        log.error("unhandled request failure", exception);
        return handle(new ApiException("INTERNAL_ERROR", "Something went wrong. Try again."), request);
    }

    private UUID correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationFilter.ATTRIBUTE);
        return value instanceof UUID id ? id : UUID.randomUUID();
    }
}
