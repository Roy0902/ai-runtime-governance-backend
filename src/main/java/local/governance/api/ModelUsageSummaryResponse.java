package local.governance.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ModelUsageSummaryResponse(
        UUID usageId,
        Long inputTokens,
        Long outputTokens,
        Long cachedInputTokens,
        Long reasoningOutputTokens,
        Long totalTokens,
        BigDecimal totalCostUsd,
        String currency,
        Integer durationMs,
        List<String> finishReasons,
        List<String> toolCallNames,
        String langfuseObservationId) {}
