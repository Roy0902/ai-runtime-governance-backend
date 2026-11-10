package local.governance.api;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

public class APIException extends RuntimeException {

    private static final Map<String, HttpStatus> STATUS = Map.ofEntries(
            Map.entry("REQUEST_INVALID", HttpStatus.BAD_REQUEST),
            Map.entry("SIGN_IN_FAILED", HttpStatus.UNAUTHORIZED),
            Map.entry("SIGN_IN_REQUIRED", HttpStatus.UNAUTHORIZED),
            Map.entry("HOOK_CREDENTIAL_INVALID", HttpStatus.UNAUTHORIZED),
            Map.entry("GATEWAY_CREDENTIAL_INVALID", HttpStatus.UNAUTHORIZED),
            Map.entry("SETUP_ACCESS_FAILED", HttpStatus.UNAUTHORIZED),
            Map.entry("CSRF_REJECTED", HttpStatus.FORBIDDEN),
            Map.entry("FORBIDDEN", HttpStatus.FORBIDDEN),
            Map.entry("RECENT_AUTH_REQUIRED", HttpStatus.FORBIDDEN),
            Map.entry("GROUP_ACCESS_DENIED", HttpStatus.FORBIDDEN),
            Map.entry("GROUP_INVITE_INVALID", HttpStatus.BAD_REQUEST),
            Map.entry("NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("PROVIDER_NOT_CONFIGURED", HttpStatus.NOT_FOUND),
            Map.entry("STALE_OPERATION", HttpStatus.CONFLICT),
            Map.entry("EVENT_CONFLICT", HttpStatus.CONFLICT),
            Map.entry("HOOK_CREDENTIAL_EXISTS", HttpStatus.CONFLICT),
            Map.entry("GATEWAY_KEY_EXISTS", HttpStatus.CONFLICT),
            Map.entry("GATEWAY_KEY_LIMIT", HttpStatus.CONFLICT),
            Map.entry("GATEWAY_NOT_ENTITLED", HttpStatus.FORBIDDEN),
            Map.entry("GATEWAY_DISABLED", HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry("MODEL_NOT_ALLOWED", HttpStatus.FORBIDDEN),
            Map.entry("GUARD_DENIED", HttpStatus.FORBIDDEN),
            Map.entry("GUARD_APPROVAL_REQUIRED", HttpStatus.FORBIDDEN),
            Map.entry("GUARD_CONTEXT_UNKNOWN", HttpStatus.CONFLICT),
            Map.entry("GUARD_EVALUATION_FAILED", HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry("PROTOCOL_UNSUPPORTED", HttpStatus.BAD_REQUEST),
            Map.entry("MESSAGE_LIMIT_EXCEEDED", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("REQUEST_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE),
            Map.entry("UPSTREAM_UNAVAILABLE", HttpStatus.BAD_GATEWAY),
            Map.entry("UPSTREAM_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT),
            Map.entry("LANGFUSE_DISABLED", HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry("LANGFUSE_UNAVAILABLE", HttpStatus.BAD_GATEWAY),
            Map.entry("SECRETS_ALREADY_ISSUED", HttpStatus.CONFLICT),
            Map.entry("LINK_NOT_USABLE", HttpStatus.GONE),
            Map.entry("OPERATION_EXPIRED", HttpStatus.GONE),
            Map.entry("RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS),
            Map.entry("TRAVERSAL_LIMIT", HttpStatus.TOO_MANY_REQUESTS),
            Map.entry("TRAVERSAL_TOO_LARGE", HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry("TRAVERSAL_EXPIRED", HttpStatus.GONE),
            Map.entry("TEMPORARILY_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry("INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR));

    private final String code;
    private final HttpStatus status;
    private final Integer retryAfterSeconds;
    private final List<FieldError> fieldErrors;

    public APIException(String code, String message) {
        this(code, message, null, List.of());
    }

    public APIException(String code, String message, Integer retryAfterSeconds) {
        this(code, message, retryAfterSeconds, List.of());
    }

    public APIException(String code, String message, Integer retryAfterSeconds, List<FieldError> fieldErrors) {
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

    public static APIException invalid(String field, String message) {
        return new APIException("REQUEST_INVALID", "Check the highlighted fields and try again.", null,
                List.of(new FieldError(field, message)));
    }

    public static APIException signInFailed() {
        return new APIException("SIGN_IN_FAILED", "Sign-in failed. Check your details and try again.");
    }

    public static APIException signInRequired() {
        return new APIException("SIGN_IN_REQUIRED", "Sign in to continue.");
    }

    public static APIException notFound() {
        return new APIException("NOT_FOUND", "We couldn't find that item.");
    }

    public static APIException unavailable() {
        return new APIException("TEMPORARILY_UNAVAILABLE", "The service is temporarily unavailable. Try again shortly.");
    }

    public static APIException rateLimited(int retryAfterSeconds) {
        return new APIException("RATE_LIMITED", "Too many attempts. Try again shortly.", retryAfterSeconds);
    }

    public static APIException traversalExpired() {
        return new APIException("TRAVERSAL_EXPIRED", "This activity view expired. Refresh and try again.");
    }

    public static APIException traversalLimit(int retryAfterSeconds) {
        return new APIException("TRAVERSAL_LIMIT", "Too many activity views are open. Try again shortly.",
                retryAfterSeconds);
    }

    public static APIException traversalTooLarge() {
        return new APIException("TRAVERSAL_TOO_LARGE", "This activity is too large to page. Narrow the view.");
    }
}
