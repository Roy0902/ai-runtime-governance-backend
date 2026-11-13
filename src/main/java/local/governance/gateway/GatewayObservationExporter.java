package local.governance.gateway;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Optional external observability sink (currently Langfuse over OTLP). A
 * missing implementation is a supported configuration: telemetry is simply not
 * exported, while the local timeline remains authoritative.
 */
public interface GatewayObservationExporter {

    record Observation(
            UUID scopeId,
            UUID accountId,
            UUID groupId,
            UUID traceId,
            String sessionId,
            String model,
            String prompt,
            String output,
            String status,
            Integer inputTokens,
            Integer outputTokens,
            Integer cachedInputTokens,
            Integer reasoningOutputTokens,
            Integer totalTokens,
            BigDecimal providerReportedCostUsd,
            BigDecimal totalCostUsd,
            String finishReason,
            long durationMs,
            String guardOutcome,
            String userEmail,
            String userName,
            String groupName) {

        public Observation(
                UUID scopeId,
                UUID accountId,
                UUID groupId,
                UUID traceId,
                String sessionId,
                String model,
                String prompt,
                String output,
                String status,
                Integer inputTokens,
                Integer outputTokens,
                Integer cachedInputTokens,
                Integer reasoningOutputTokens,
                Integer totalTokens,
                BigDecimal providerReportedCostUsd,
                BigDecimal totalCostUsd,
                String finishReason,
                long durationMs,
                String guardOutcome) {
            this(
                    scopeId,
                    accountId,
                    groupId,
                    traceId,
                    sessionId,
                    model,
                    prompt,
                    output,
                    status,
                    inputTokens,
                    outputTokens,
                    cachedInputTokens,
                    reasoningOutputTokens,
                    totalTokens,
                    providerReportedCostUsd,
                    totalCostUsd,
                    finishReason,
                    durationMs,
                    guardOutcome,
                    null,
                    null,
                    null);
        }
    }

    void export(Observation observation);
}
