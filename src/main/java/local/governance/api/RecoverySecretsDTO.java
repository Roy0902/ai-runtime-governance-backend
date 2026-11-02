package local.governance.api;

import java.util.List;

public record RecoverySecretsDTO(OperationDTO operation, List<String> recoveryCodes) {}
