package local.governance.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import java.math.BigDecimal;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.UUID;
import local.governance.config.GovernanceProperties;
import local.governance.gateway.GatewayObservationExporter.Observation;
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

        String observationId = exporter.export(new GatewayObservationExporter.Observation(
                scopeId,
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
        assertEquals(generation.getSpanId(), observationId);
        assertEquals(traceId.toString().replace("-", ""), generation.getTraceId());
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
        assertEquals(generation.getTraceId(), guard.getTraceId());
        assertEquals("guardrail", string(guard, "langfuse.observation.type"));
        assertEquals("redact", string(guard, "langfuse.observation.output"));
    }

    @Test
    void groupsCallsOfOneTraceUnderASingleOtelTrace() {
        UUID traceId = Uuid7.generate();
        Observation first = observation(traceId);
        Observation second = observation(traceId);

        String firstId = exporter.export(first);
        String secondId = exporter.export(second);

        String expected = traceId.toString().replace("-", "");
        assertNotNull(firstId);
        assertNotNull(secondId);
        assertTrue(!firstId.equals(secondId));
        List<SpanData> generations = inMemory.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals("chat gpt-4o"))
                .toList();
        assertEquals(2, generations.size());
        assertTrue(generations.stream().allMatch(span -> span.getTraceId().equals(expected)));
        assertEquals(
                2,
                generations.stream().map(SpanData::getSpanId).distinct().count());
    }

    private static Observation observation(UUID traceId) {
        return new Observation(
                UUID.randomUUID(),
                traceId,
                "conversation-key",
                "gpt-4o",
                "prompt",
                "output",
                "completed",
                1,
                1,
                null,
                null,
                2,
                null,
                null,
                "stop",
                10,
                "allow");
    }

    @Test
    void doesNotExportWhenKeysAreMissing() {
        GovernanceProperties disabled = new GovernanceProperties();
        LangfuseObservationExporter noKeys =
                new LangfuseObservationExporter(disabled, new ObjectMapper());

        assertNull(noKeys.export(new GatewayObservationExporter.Observation(
                UUID.randomUUID(), UUID.randomUUID(), null, "gpt-4o", null, "x",
                "completed", null, null, null, null, null, null, null, null, 1, "allow")));
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
