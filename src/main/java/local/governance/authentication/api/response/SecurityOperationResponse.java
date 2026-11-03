package local.governance.authentication.api.response;

import java.time.Instant;
import java.util.UUID;

public record SecurityOperationResponse(
        UUID operationId,
        String purpose,
        String state,
        Instant createdAt,
        Instant expiresAt) {}
