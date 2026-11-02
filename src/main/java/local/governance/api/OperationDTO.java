package local.governance.api;

import java.time.Instant;
import java.util.UUID;

public record OperationDTO(
        UUID operationId,
        String purpose,
        String state,
        Instant createdAt,
        Instant expiresAt) {}
