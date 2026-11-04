package local.governance.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ModelUsageRecorder {

    public record Usage(
            UUID scopeId,
            UUID traceId,
            UUID credentialId,
            String conversationKey,
            String clientConversationId,
            String clientUserAgent,
            String provider,
            String requestedModel,
            String responseModel,
            String responseId,
            String systemFingerprint,
            String serviceTier,
            boolean stream,
            Integer statusCode,
            String outcome,
            List<String> finishReasons,
            List<String> toolCallNames,
            Long inputTokens,
            Long outputTokens,
            Long cachedInputTokens,
            Long reasoningOutputTokens,
            Long totalTokens,
            BigDecimal providerReportedCostUsd,
            BigDecimal inputCostUsd,
            BigDecimal outputCostUsd,
            BigDecimal cachedInputCostUsd,
            BigDecimal totalCostUsd,
            String currency,
            Integer requestBytes,
            Long responseBytes,
            boolean captureTruncated,
            Integer durationMs,
            Integer timeToFirstByteMs,
            String errorType,
            String errorCode,
            String errorMessage,
            Map<String, Object> providerUsage,
            Map<String, String> responseHeaders) {}

    private final ModelUsageRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ModelUsageRecorder(
            ModelUsageRepository repository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void record(Usage usage) {
        repository.insert(new ModelUsageRow(
                UUID.randomUUID(),
                usage.scopeId(),
                usage.traceId(),
                usage.credentialId(),
                usage.conversationKey(),
                usage.clientConversationId(),
                usage.clientUserAgent(),
                usage.provider(),
                usage.requestedModel(),
                usage.responseModel(),
                usage.responseId(),
                usage.systemFingerprint(),
                usage.serviceTier(),
                usage.stream(),
                usage.statusCode(),
                usage.outcome(),
                usage.finishReasons() == null ? List.of() : usage.finishReasons(),
                usage.toolCallNames() == null ? List.of() : usage.toolCallNames(),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cachedInputTokens(),
                usage.reasoningOutputTokens(),
                usage.totalTokens(),
                usage.providerReportedCostUsd(),
                usage.inputCostUsd(),
                usage.outputCostUsd(),
                usage.cachedInputCostUsd(),
                usage.totalCostUsd(),
                usage.currency(),
                usage.requestBytes(),
                usage.responseBytes(),
                usage.captureTruncated(),
                usage.durationMs(),
                usage.timeToFirstByteMs(),
                usage.errorType(),
                usage.errorCode(),
                usage.errorMessage(),
                json(usage.providerUsage()),
                json(usage.responseHeaders()),
                clock.instant()));
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return null;
        }
    }
}
