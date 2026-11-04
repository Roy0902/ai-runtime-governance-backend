package local.governance.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.UUID;
import local.governance.config.GovernanceProperties;
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
                traceId,
                "conversation-key",
                "gpt-4o",
                "email [REDACTED_EMAIL]",
                "the answer",
                "completed",
                7,
                3,
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
                UUID.randomUUID(), UUID.randomUUID(), null, "gpt-4o", null, "x",
                "completed", null, null, null, 1, "allow"));
        noKeys.shutdown();

        assertTrue(inMemory.getFinishedSpanItems().isEmpty());
    }

    private static SpanData span(List<SpanData> spans, String name) {
        return spans.stream().filter(span -> span.getName().equals(name)).findFirst().orElse(null);
    }

    private static String string(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
    }
}
