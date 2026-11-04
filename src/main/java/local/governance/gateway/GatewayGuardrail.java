package local.governance.gateway;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;
import java.util.List;
import local.governance.guard.GuardOutcome;
import local.governance.guard.GuardResult;
import org.springframework.stereotype.Component;

/**
 * LangChain4j input guardrail over the existing deterministic detector
 * catalog. The structured {@link Decision} is what the gateway uses for
 * persistence and error handling; {@link #validate(UserMessage)} exposes the
 * same decision through LangChain4j's guardrail contract.
 */
@Component
public class GatewayGuardrail implements InputGuardrail {

    public static final String POLICY_ID = "BUILTIN";
    public static final String POLICY_VERSION = "2026-11-04";

    public record Decision(
            GuardOutcome outcome,
            String message,
            List<String> ruleIds,
            List<String> reasons) {

        public boolean failed() {
            return outcome == null;
        }
    }

    private final local.governance.guard.GuardDetectorCatalog catalog;

    public GatewayGuardrail(local.governance.guard.GuardDetectorCatalog catalog) {
        this.catalog = catalog;
    }

    public Decision evaluate(String message) {
        GuardResult result = catalog.evaluate(POLICY_ID, POLICY_VERSION, message);
        if (result instanceof GuardResult.GuardFailure failure) {
            return new Decision(null, message, List.of(), failure.reasons());
        }
        GuardResult.GuardDecision decision = (GuardResult.GuardDecision) result;
        return new Decision(
                decision.outcome(),
                decision.message(),
                decision.matchedRuleIds(),
                List.of());
    }

    @Override
    public InputGuardrailResult validate(UserMessage userMessage) {
        Decision decision = evaluate(userMessage == null ? null : userMessage.singleText());
        if (decision.failed()) {
            return fatal("GUARD_EVALUATION_FAILED");
        }
        return switch (decision.outcome()) {
            case ALLOW -> success();
            case REDACT -> successWith(decision.message());
            case DENY -> fatal("GUARD_DENIED");
            case REQUIRE_APPROVAL -> fatal("GUARD_APPROVAL_REQUIRED");
        };
    }
}
