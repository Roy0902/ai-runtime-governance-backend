package local.governance.authentication.api.response;

import java.util.List;

public record RecoveryCodeGenerationResponse(SecurityOperationResponse operation, List<String> recoveryCodes) {}
