package local.governance.authentication.api.request;

import java.util.UUID;

public record RestartEnrollmentRequest(UUID operationId, String password) {}
