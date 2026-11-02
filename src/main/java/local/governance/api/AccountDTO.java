package local.governance.api;

import java.util.UUID;

public record AccountDTO(
        UUID accountId,
        String email,
        boolean emailVerified,
        String mfaState,
        UUID scopeId,
        String gatewayEntitlement,
        SessionDTO session) {}
