package local.governance.integration.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import local.governance.timeline.ActivityMetadataService;

public final class HookIngestRequest {

    public UUID traceId;
    public ActivityMetadataService.IngestEvent event;

    private final Map<String, Object> unknown = new LinkedHashMap<>();

    @JsonAnySetter
    public void capture(String name, Object value) {
        unknown.put(name, value);
    }

    public boolean hasUnknown() {
        return !unknown.isEmpty();
    }
}
