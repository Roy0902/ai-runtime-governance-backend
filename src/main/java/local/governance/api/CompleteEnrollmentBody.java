package local.governance.api;

import java.util.UUID;

public record CompleteEnrollmentBody(
        UUID operationId,
        String password,
        String authenticatorCode,
        boolean recoveryCodesSaved) {}
