package local.governance.gateway.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ModelUsageResponse(
        UUID id,
        UUID traceId,
        UUID credentialId,
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
        Map<String, String> responseHeaders,
        String langfuseObservationId,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
        Instant receivedAt) {}
