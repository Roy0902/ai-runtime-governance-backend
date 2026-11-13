package local.governance.timeline;

import java.util.Map;
import java.util.UUID;
import local.governance.api.ModelUsageSummaryResponse;

/** Read-side port for model usage linked to timeline events. */
@FunctionalInterface
public interface EventUsageLookup {

    Map<UUID, ModelUsageSummaryResponse> byEvent(UUID scopeId, UUID traceId);
}
