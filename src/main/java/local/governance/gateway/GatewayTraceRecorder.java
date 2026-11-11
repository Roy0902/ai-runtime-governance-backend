package local.governance.gateway;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import local.governance.guard.GuardOutcome;
import local.governance.timeline.TimelineRepository;
import local.governance.timeline.TimelineRepository.EventRow;
import local.governance.timeline.TimelineRepository.TraceRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the authoritative gateway-owned parts of a trace. Guard decisions are
 * committed before the upstream call so a successful dispatch always has a
 * recorded decision behind it.
 */
@Service
public class GatewayTraceRecorder {

    private final TimelineRepository timeline;
    private final Clock clock;

    public GatewayTraceRecorder(TimelineRepository timeline, Clock clock) {
        this.timeline = timeline;
        this.clock = clock;
    }

    @Transactional
    public void beginGuardedTurn(
            UUID scopeId,
            UUID traceId,
            String model,
            GuardOutcome outcome,
            String policyId,
            String policyVersion,
            List<String> ruleIds) {
        Instant now = clock.instant();
        timeline.insertTrace(new TraceRow(
                traceId,
                scopeId,
                now,
                "running",
                "complete",
                outcome == null ? null : outcome.name().toLowerCase(java.util.Locale.ROOT),
                model,
                "guarded",
                "unknown",
                "unknown",
                null,
                null,
                null,
                null));
        timeline.insertEvent(new EventRow(
                UUID.randomUUID(),
                traceId,
                scopeId,
                null,
                false,
                "prompt",
                now,
                now,
                null,
                model,
                "chat",
                null,
                null,
                null,
                List.of(),
                null,
                null,
                "completed"));
        timeline.insertEvent(new EventRow(
                UUID.randomUUID(),
                traceId,
                scopeId,
                null,
                false,
                "guard",
                now,
                now,
                null,
                "gateway",
                "guard",
                outcome == null ? null : outcome.name().toLowerCase(java.util.Locale.ROOT),
                policyId,
                policyVersion,
                ruleIds == null ? List.of() : ruleIds,
                null,
                null,
                outcome == null ? "failed" : "completed"));
    }

    @Transactional
    public void beginBypassedTurn(UUID scopeId, UUID traceId, String model) {
        Instant now = clock.instant();
        timeline.insertTrace(new TraceRow(
                traceId,
                scopeId,
                now,
                "running",
                "complete",
                null,
                model,
                "bypassed",
                "unknown",
                "unknown",
                null,
                null,
                null,
                null));
    }

    @Transactional
    public void blocked(
            UUID scopeId,
            UUID traceId,
            String model,
            GuardOutcome outcome,
            String policyId,
            String policyVersion,
            List<String> ruleIds,
            boolean evaluationFailed) {
        beginGuardedTurn(scopeId, traceId, model, outcome, policyId, policyVersion, ruleIds);
        timeline.updateTraceCompletion(
                scopeId,
                traceId,
                evaluationFailed ? "failed" : "completed",
                "unsupported",
                "unsupported");
    }

    @Transactional
    public void dispatchFailed(UUID scopeId, UUID traceId) {
        timeline.updateTraceCompletion(scopeId, traceId, "failed", "unknown", "unknown");
    }

    @Transactional
    public void complete(
            UUID scopeId,
            UUID traceId,
            String model,
            String status,
            String finishReason,
            Integer durationMs,
            UUID eventId) {
        Instant now = clock.instant();
        timeline.insertEvent(new EventRow(
                eventId,
                traceId,
                scopeId,
                null,
                false,
                "model",
                now,
                now,
                durationMs,
                model,
                finishReason,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                status));
        // A tool call in a model response proves the model asked for a tool, not
        // that the tool ran. Tool coverage stays unknown until a hook reports it.
        timeline.updateTraceCompletion(
                scopeId,
                traceId,
                status,
                "observed",
                "unknown");
    }
}
