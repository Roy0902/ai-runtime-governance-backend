package local.governance.api;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private static final Map<String, HttpStatus> STATUS = Map.ofEntries(
            Map.entry("REQUEST_INVALID", HttpStatus.BAD_REQUEST),
            Map.entry("SIGN_IN_FAILED", HttpStatus.UNAUTHORIZED),
            Map.entry("SIGN_IN_REQUIRED", HttpStatus.UNAUTHORIZED),
            Map.entry("SETUP_ACCESS_FAILED", HttpStatus.UNAUTHORIZED),
            Map.entry("CSRF_REJECTED", HttpStatus.FORBIDDEN),
            Map.entry("FORBIDDEN", HttpStatus.FORBIDDEN),
            Map.entry("RECENT_AUTH_REQUIRED", HttpStatus.FORBIDDEN),
            Map.entry("NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("STALE_OPERATION", HttpStatus.CONFLICT),
            Map.entry("SECRETS_ALREADY_ISSUED", HttpStatus.CONFLICT),
            Map.entry("LINK_NOT_USABLE", HttpStatus.GONE),
            Map.entry("OPERATION_EXPIRED", HttpStatus.GONE),
            Map.entry("RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS),
            Map.entry("TEMPORARILY_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry("INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR));

    private final String code;
    private final HttpStatus status;
    private final Integer retryAfterSeconds;
    private final List<FieldError> fieldErrors;

    public ApiException(String code, String message) {
        this(code, message, null, List.of());
    }

    public ApiException(String code, String message, Integer retryAfterSeconds) {
        this(code, message, retryAfterSeconds, List.of());
    }

    public ApiException(String code, String message, Integer retryAfterSeconds, List<FieldError> fieldErrors) {
        super(message);
        this.code = code;
        this.status = STATUS.getOrDefault(code, HttpStatus.INTERNAL_SERVER_ERROR);
        this.retryAfterSeconds = retryAfterSeconds;
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }

    public record FieldError(String field, String message) {}

    public static ApiException invalid(String field, String message) {
        return new ApiException("REQUEST_INVALID", "Check the highlighted fields and try again.", null,
                List.of(new FieldError(field, message)));
    }

    public static ApiException signInFailed() {
        return new ApiException("SIGN_IN_FAILED", "Sign-in failed. Check your details and try again.");
    }

    public static ApiException signInRequired() {
        return new ApiException("SIGN_IN_REQUIRED", "Sign in to continue.");
    }

    public static ApiException notFound() {
        return new ApiException("NOT_FOUND", "We couldn't find that item.");
    }

    public static ApiException unavailable() {
        return new ApiException("TEMPORARILY_UNAVAILABLE", "The service is temporarily unavailable. Try again shortly.");
    }

    public static ApiException rateLimited(int retryAfterSeconds) {
        return new ApiException("RATE_LIMITED", "Too many attempts. Try again shortly.", retryAfterSeconds);
    }
}
