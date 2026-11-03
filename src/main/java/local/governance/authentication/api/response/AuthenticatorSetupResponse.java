package local.governance.authentication.api.response;

import java.util.List;

public record AuthenticatorSetupResponse(
        SecurityOperationResponse operation,
        String manualKey,
        String provisioningUri,
        List<String> recoveryCodes) {}
