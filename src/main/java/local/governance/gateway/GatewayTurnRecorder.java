package local.governance.gateway;

import java.util.List;
import java.util.UUID;
import local.governance.guard.GuardOutcome;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commits the guarded turn and its conversation correlation atomically. A
 * failure here is a pre-dispatch failure: the caller must not forward.
 */
@Service
public class GatewayTurnRecorder {

    private final GatewayTraceRecorder traces;
    private final GatewayConversationService conversations;

    public GatewayTurnRecorder(
            GatewayTraceRecorder traces,
            GatewayConversationService conversations) {
        this.traces = traces;
        this.conversations = conversations;
    }

    @Transactional
    public void recordGuardedTurn(
            UUID scopeId,
            UUID traceId,
            String model,
            GuardOutcome outcome,
            String policyId,
            String policyVersion,
            List<String> ruleIds,
            String conversationKey,
            String guardedMessageHash) {
        traces.beginGuardedTurn(scopeId, traceId, model, outcome, policyId, policyVersion, ruleIds);
        conversations.record(
                scopeId,
                traceId,
                conversationKey,
                guardedMessageHash,
                outcome.name().toLowerCase(java.util.Locale.ROOT));
    }

    @Transactional
    public void recordBypassedTurn(UUID scopeId, UUID traceId, String model) {
        traces.beginBypassedTurn(scopeId, traceId, model);
    }
}
