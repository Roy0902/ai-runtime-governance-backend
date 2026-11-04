package local.governance.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import local.governance.api.APIException;
import local.governance.integration.HookCredentialService.Principal;
import local.governance.integration.api.HookIngestRequest;
import local.governance.timeline.ActivityMetadataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HookIngestionServiceTest {

    private HookCredentialService credentials;
    private ActivityMetadataService metadata;
    private HookIngestionService service;

    @BeforeEach
    void setUp() {
        credentials = mock(HookCredentialService.class);
        metadata = mock(ActivityMetadataService.class);
        service = new HookIngestionService(credentials, metadata);
    }

    @Test
    void keepsIntegrationSourcesIndependentAndUsesCredentialScope() {
        UUID accountId = UUID.randomUUID();
        UUID scopeId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        ActivityMetadataService.IngestEvent event = new ActivityMetadataService.IngestEvent();
        HookIngestRequest request = request(traceId, event);
        when(credentials.authenticate(HookIntegration.LANGFUSE, "langfuse-token"))
                .thenReturn(new Principal(accountId, scopeId));
        when(metadata.acceptFromHook(scopeId, traceId, "langfuse", event))
                .thenReturn(new ActivityMetadataService.AcceptResult(true, false));

        HookIngestionService.Receipt receipt =
                service.ingest(HookIntegration.LANGFUSE, "langfuse-token", request);

        assertEquals(new HookIngestionService.Receipt(true, false), receipt);
        verify(metadata).acceptFromHook(scopeId, traceId, "langfuse", event);
        verify(credentials).authenticate(HookIntegration.LANGFUSE, "langfuse-token");
    }

    @Test
    void rejectsUnsupportedTopLevelFieldsBeforePersisting() {
        UUID scopeId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        when(credentials.authenticate(HookIntegration.LANGCHAIN4J, "langchain-token"))
                .thenReturn(new Principal(UUID.randomUUID(), scopeId));
        HookIngestRequest request = request(traceId, new ActivityMetadataService.IngestEvent());
        request.capture("prompt", "synthetic content");

        APIException exception = assertThrows(
                APIException.class,
                () -> service.ingest(HookIntegration.LANGCHAIN4J, "langchain-token", request));

        assertEquals("REQUEST_INVALID", exception.code());
        verify(metadata, never()).acceptFromHook(
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    private HookIngestRequest request(UUID traceId, ActivityMetadataService.IngestEvent event) {
        HookIngestRequest request = new HookIngestRequest();
        request.traceId = traceId;
        request.event = event;
        return request;
    }
}
