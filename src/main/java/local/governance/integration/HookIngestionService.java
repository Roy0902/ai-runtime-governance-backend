package local.governance.integration;

import local.governance.api.APIException;
import local.governance.integration.HookCredentialService.Principal;
import local.governance.integration.api.HookIngestRequest;
import local.governance.timeline.ActivityMetadataService;
import org.springframework.stereotype.Service;

@Service
public class HookIngestionService {

    public record Receipt(boolean accepted, boolean duplicate) {}

    private final HookCredentialService credentials;
    private final ActivityMetadataService metadata;

    public HookIngestionService(HookCredentialService credentials, ActivityMetadataService metadata) {
        this.credentials = credentials;
        this.metadata = metadata;
    }

    public Receipt ingest(HookIntegration integration, String token, HookIngestRequest request) {
        Principal principal = credentials.authenticate(integration, token);
        if (request == null || request.hasUnknown() || request.traceId == null || request.event == null) {
            throw APIException.invalid("request", "Provide a trace id, event, and supported fields.");
        }
        ActivityMetadataService.AcceptResult result =
                metadata.acceptFromHook(principal.scopeId(), request.traceId, integration.id(), request.event);
        return new Receipt(result.accepted(), result.duplicate());
    }
}
