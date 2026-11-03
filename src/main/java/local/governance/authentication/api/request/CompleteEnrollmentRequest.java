package local.governance.authentication.api.request;

import java.util.UUID;

public record CompleteEnrollmentRequest(
        UUID operationId,
        String password,
        String authenticatorCode,
        boolean recoveryCodesSaved) {}
