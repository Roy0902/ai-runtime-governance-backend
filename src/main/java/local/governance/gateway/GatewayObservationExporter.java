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
            String guardOutcome) {}

    void export(Observation observation);
}
