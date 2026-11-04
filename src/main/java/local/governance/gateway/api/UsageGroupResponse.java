package local.governance.gateway.api;

import java.math.BigDecimal;

public record UsageGroupResponse(
        String key,
        long calls,
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        long reasoningOutputTokens,
        long totalTokens,
        BigDecimal totalCostUsd) {}
