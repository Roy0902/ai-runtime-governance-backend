package local.governance.api;

import java.util.List;
import java.util.UUID;

public record FailureBody(
        String code,
        String message,
        UUID correlationId,
        Integer retryAfterSeconds,
        List<ApiException.FieldError> fieldErrors) {
}
