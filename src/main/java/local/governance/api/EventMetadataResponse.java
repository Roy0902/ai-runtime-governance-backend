package local.governance.api;

import java.util.List;
import java.util.UUID;

public record EventMetadataResponse(
        String guardOutcome,
        String outcome,
        List<String> ruleIds,
        String policyId,
        String policyVersion,
        UUID approvalId,
        String state,
        String name,
        String status) {}
