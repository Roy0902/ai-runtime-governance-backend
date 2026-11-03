package local.governance.authentication.api.response;

import java.time.Instant;

public record AuthenticationContextResponse(
        String state,
        String csrfToken,
        Instant setupExpiresAt,
        String enrollmentPurpose,
        AccountResponse account) {}
