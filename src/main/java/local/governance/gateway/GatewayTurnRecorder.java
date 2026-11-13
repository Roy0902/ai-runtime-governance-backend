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
    public UUID recordGuardedTurn(
            UUID scopeId,
            UUID traceId,
            String model,
            GuardOutcome outcome,
            String policyId,
            String policyVersion,
            List<String> ruleIds,
            String conversationKey,
            String guardedMessageHash,
            String promptContent,
            String sessionId) {
        UUID promptId = traces.beginGuardedTurn(
                scopeId,
                traceId,
                model,
                outcome,
                policyId,
                policyVersion,
                ruleIds,
                promptContent,
                sessionId);
        conversations.record(
                scopeId,
                traceId,
                conversationKey,
                guardedMessageHash,
                outcome.name().toLowerCase(java.util.Locale.ROOT));
        return promptId;
    }

    @Transactional
    public UUID recordBypassedTurn(
            UUID scopeId,
            UUID traceId,
            String model,
            String promptContent,
            String sessionId) {
        return traces.beginBypassedTurn(scopeId, traceId, model, promptContent, sessionId);
    }
}
