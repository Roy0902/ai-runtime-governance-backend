package local.governance.gateway.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record UsageSummaryResponse(
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
        Instant from,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
        Instant to,
        long calls,
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        long reasoningOutputTokens,
        long totalTokens,
        BigDecimal totalCostUsd,
        String currency,
        List<UsageGroupResponse> byModel,
        List<UsageGroupResponse> byDay) {}
