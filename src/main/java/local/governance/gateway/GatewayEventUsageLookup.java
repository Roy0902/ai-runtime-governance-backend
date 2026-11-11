package local.governance.gateway;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import local.governance.api.ModelUsageSummaryResponse;
import local.governance.timeline.EventUsageLookup;
import org.springframework.stereotype.Component;

@Component
public class GatewayEventUsageLookup implements EventUsageLookup {

    private final ModelUsageRepository usage;

    public GatewayEventUsageLookup(ModelUsageRepository usage) {
        this.usage = usage;
    }

    @Override
    public Map<UUID, ModelUsageSummaryResponse> byEvent(UUID scopeId, UUID traceId) {
        return usage.forTrace(scopeId, traceId).stream()
                .filter((row) -> row.eventId() != null)
                .collect(Collectors.toMap(
                        ModelUsageRow::eventId,
                        GatewayEventUsageLookup::response,
                        (first, second) -> first));
    }

    private static ModelUsageSummaryResponse response(ModelUsageRow row) {
        return new ModelUsageSummaryResponse(
                row.id(),
                row.inputTokens(),
                row.outputTokens(),
                row.cachedInputTokens(),
                row.reasoningOutputTokens(),
                row.totalTokens(),
                row.totalCostUsd(),
                row.currency(),
                row.durationMs(),
                row.finishReasons(),
                row.toolCallNames(),
                row.langfuseObservationId());
    }
}
