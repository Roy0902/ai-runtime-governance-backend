package local.governance.api;

import java.util.List;

public record AuthenticatorSecretsDTO(
        OperationDTO operation,
        String manualKey,
        String provisioningUri,
        List<String> recoveryCodes) {}
