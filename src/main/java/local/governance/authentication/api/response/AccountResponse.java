package local.governance.authentication.api.response;

import java.util.UUID;

public record AccountResponse(
        UUID accountId,
        String email,
        boolean emailVerified,
        String mfaState,
        UUID scopeId,
        String gatewayEntitlement,
        SessionLifetimeResponse session) {}
