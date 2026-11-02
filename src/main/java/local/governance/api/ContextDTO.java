package local.governance.api;

import java.time.Instant;

public record ContextDTO(
        String state,
        String csrfToken,
        Instant setupExpiresAt,
        String enrollmentPurpose,
        AccountDTO account) {}
