package local.governance.guard;

import java.util.List;

public sealed interface GuardResult {

    record GuardDecision(
            GuardOutcome outcome,
            String message,
            String policyId,
            String policyVersion,
            List<String> matchedRuleIds) implements GuardResult {}

    record GuardFailure(List<String> reasons) implements GuardResult {}
}
