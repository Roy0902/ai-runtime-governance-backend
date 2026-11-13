package local.governance.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *
 * <p>Events are stored as three layers: the trace is the session, every guarded
 * human turn is a prompt event, and guard/model/tool events are its children.
 */
@Service
public class GatewayTraceRecorder {

    private final TimelineRepository timeline;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ContentSanitizer sanitizer;

    public GatewayTraceRecorder(TimelineRepository timeline, Clock clock, ObjectMapper objectMapper) {
        this.timeline = timeline;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.sanitizer = new ContentSanitizer(objectMapper);
    }

    /**
     * Resolves the trace that represents a client session. Every human turn in
     * one session appends its prompt events to the same trace, so the timeline
     * groups prompts under the session instead of showing one trace per prompt.
     */
    public UUID resolveTraceId(UUID scopeId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Uuid7.generate();
        }
        return timeline.traceIdForSession(scopeId, sessionId).orElseGet(Uuid7::generate);
    }

    @Transactional
    public UUID beginGuardedTurn(
            UUID scopeId,
            UUID traceId,
            String model,
            GuardOutcome outcome,
            String policyId,
            String policyVersion,
            List<String> ruleIds,
            String promptContent,
            String sessionId) {
        Instant now = clock.instant();
        timeline.insertTraceIfAbsent(new TraceRow(
                traceId,
                scopeId,
                now,
                "running",
                "complete",
                outcome == null ? null : outcome.name().toLowerCase(java.util.Locale.ROOT),
                model,
                sessionId,
                "guarded",
                "unknown",
                "unknown",
                null,
                null,
                null,
                null));
        UUID promptId = UUID.randomUUID();
        timeline.insertEvent(new EventRow(
                promptId,
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
                "completed",
                promptContent));
        timeline.insertEvent(new EventRow(
                UUID.randomUUID(),
                traceId,
                scopeId,
                promptId,
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
                outcome == null ? "failed" : "completed",
                null));
        return promptId;
    }

    @Transactional
    public UUID beginBypassedTurn(
            UUID scopeId,
            UUID traceId,
            String model,
            String promptContent,
            String sessionId) {
        Instant now = clock.instant();
        timeline.insertTraceIfAbsent(new TraceRow(
                traceId,
                scopeId,
                now,
                "running",
                "complete",
                null,
                model,
                sessionId,
                "bypassed",
                "unknown",
                "unknown",
                null,
                null,
                null,
                null));
        UUID promptId = UUID.randomUUID();
        timeline.insertEvent(new EventRow(
                promptId,
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
                "completed",
                promptContent));
        return promptId;
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
            boolean evaluationFailed,
            String promptContent,
            String sessionId) {
        beginGuardedTurn(
                scopeId,
                traceId,
                model,
                outcome,
                policyId,
                policyVersion,
                ruleIds,
                promptContent,
                sessionId);
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
            UUID eventId,
            UUID promptEventId,
            String modelContent,
            List<GatewayResponseCapture.ToolCall> toolCalls) {
        Instant now = clock.instant();
        UUID parent = promptEventId != null
                ? promptEventId
                : timeline.latestPromptEventId(scopeId, traceId).orElse(null);
        timeline.insertEvent(new EventRow(
                eventId,
                traceId,
                scopeId,
                parent,
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
                status,
                modelContent));
        if (toolCalls != null) {
            for (GatewayResponseCapture.ToolCall call : toolCalls) {
                timeline.insertEvent(new EventRow(
                        UUID.randomUUID(),
                        traceId,
                        scopeId,
                        parent,
                        false,
                        "tool",
                        now,
                        now,
                        null,
                        model,
                        call.name(),
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        "unknown",
                        toolContent(call)));
            }
        }
        // A tool call in a model response proves the model asked for a tool, not
        // that the tool ran. Tool coverage stays unknown until a hook reports it.
        timeline.updateTraceCompletion(
                scopeId,
                traceId,
                status,
                "observed",
                "unknown");
    }

    private String toolContent(GatewayResponseCapture.ToolCall call) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("toolCallId", call.id());
        content.put("name", call.name());
        content.put("arguments", call.arguments());
        try {
            return sanitizer.clean(objectMapper.writeValueAsString(content));
        } catch (JsonProcessingException exception) {
            return null;
        }
    }
}
