package local.governance.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import java.math.BigDecimal;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import local.governance.config.GovernanceProperties;
import local.governance.provider.ProviderCredentialService;
import local.governance.provider.ProviderKind;
import org.junit.jupiter.api.Test;

class LangfuseObservationExporterTest {

    private final InMemorySpanExporter inMemory = InMemorySpanExporter.create();
    private final GovernanceProperties properties = new GovernanceProperties();
    private final LangfuseObservationExporter exporter =
            new LangfuseObservationExporter(properties, new ObjectMapper(), inMemory);

    @Test
    void exportsARedactedGenerationWithSessionAndUsage() {
        UUID scopeId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();

        exporter.export(new GatewayObservationExporter.Observation(
                scopeId,
                UUID.randomUUID(),
                null,
                traceId,
                "conversation-key",
                "gpt-4o",
                "email [REDACTED_EMAIL]",
                "the answer",
                "completed",
                7,
                3,
                2,
                1,
                10,
                new BigDecimal("0.01000000"),
                new BigDecimal("0.02000000"),
                "stop",
                120,
                "redact"));

        List<SpanData> spans = inMemory.getFinishedSpanItems();
        SpanData generation = span(spans, "chat gpt-4o");
        assertNotNull(generation);
        assertEquals("gateway gpt-4o", string(generation, "langfuse.trace.name"));
        assertEquals(scopeId.toString(), string(generation, "langfuse.user.id"));
        assertEquals("conversation-key", string(generation, "langfuse.session.id"));
        assertEquals("generation", string(generation, "langfuse.observation.type"));
        assertEquals("gpt-4o", string(generation, "langfuse.observation.model.name"));
        assertTrue(string(generation, "langfuse.observation.input").contains("[REDACTED_EMAIL]"));
        assertTrue(!string(generation, "langfuse.observation.input").contains("person@example.test"));
        assertEquals("the answer", string(generation, "langfuse.observation.output"));
        assertEquals(7L, generation.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
        assertEquals(3L, generation.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens")));
        assertEquals(2L, generation.getAttributes().get(AttributeKey.longKey("gen_ai.usage.cache_read.input_tokens")));
        assertTrue(string(generation, "langfuse.observation.cost_details").contains("0.02"));
        assertEquals(traceId.toString(), string(generation, "langfuse.trace.metadata.trace_id"));

        SpanData guard = span(spans, "prompt-guard");
        assertNotNull(guard);
        assertEquals("guardrail", string(guard, "langfuse.observation.type"));
        assertEquals("redact", string(guard, "langfuse.observation.output"));
    }

    @Test
    void doesNotExportWhenKeysAreMissing() {
        GovernanceProperties disabled = new GovernanceProperties();
        LangfuseObservationExporter noKeys =
                new LangfuseObservationExporter(disabled, new ObjectMapper());

        noKeys.export(new GatewayObservationExporter.Observation(
                UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), null, "gpt-4o", null, "x",
                "completed", null, null, null, null, null, null, null, null, 1, "allow"));
        noKeys.shutdown();

        assertTrue(inMemory.getFinishedSpanItems().isEmpty());
    }

    @Test
    void resolvesTheGroupCredentialForAGroupKey() {
        ProviderCredentialService credentials = mock(ProviderCredentialService.class);
        UUID groupId = UUID.randomUUID();
        when(credentials.resolveForGroup(groupId, ProviderKind.LANGFUSE))
                .thenReturn(Optional.of(new ProviderCredentialService.Resolved("pk-lf-group", "sk-lf-group")));
        GovernanceProperties scoped = new GovernanceProperties();
        scoped.getLangfuse().setBaseUrl("http://127.0.0.1:1");
        scoped.getLangfuse().setTimeoutSeconds(1);
        LangfuseObservationExporter scopedExporter =
                new LangfuseObservationExporter(scoped, new ObjectMapper(), credentials);

        scopedExporter.export(new GatewayObservationExporter.Observation(
                UUID.randomUUID(), UUID.randomUUID(), groupId, UUID.randomUUID(), null, "gpt-4o",
                "prompt", "output", "completed", 1, 1, null, null, 2, null, null, "stop", 1, "allow"));
        scopedExporter.shutdown();

        verify(credentials).resolveForGroup(groupId, ProviderKind.LANGFUSE);
    }

    private static SpanData span(List<SpanData> spans, String name) {
        return spans.stream().filter(span -> span.getName().equals(name)).findFirst().orElse(null);
    }

    private static String string(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
    }
}
