package local.governance.api;

import java.util.UUID;

public record RestartEnrollmentBody(UUID operationId, String password) {}
