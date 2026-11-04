package local.governance.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import local.governance.config.GovernanceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Exports redacted generations to Langfuse through its OpenTelemetry endpoint.
 * Export is asynchronous and bounded; a Langfuse outage never fails the model
 * request or changes the local timeline.
 */
@Component
@ConditionalOnProperty(name = "governance.langfuse.enabled", havingValue = "true")
public class LangfuseObservationExporter implements GatewayObservationExporter {

    private static final Logger log = LoggerFactory.getLogger(LangfuseObservationExporter.class);
    private static final AttributeKey<List<String>> TAGS = AttributeKey.stringArrayKey("langfuse.trace.tags");

    private final GovernanceProperties properties;
    private final ObjectMapper objectMapper;
    private final SdkTracerProvider provider;
    private final Tracer tracer;

    @Autowired
    public LangfuseObservationExporter(GovernanceProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, null);
    }

    LangfuseObservationExporter(
            GovernanceProperties properties,
            ObjectMapper objectMapper,
            SpanExporter testExporter) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        GovernanceProperties.LangfuseProperties langfuse = properties.getLangfuse();
        if (testExporter == null
                && (isBlank(langfuse.getPublicKey()) || isBlank(langfuse.getSecretKey()))) {
            log.warn("Langfuse is enabled but its keys are incomplete; observations will not be exported");
            this.provider = null;
            this.tracer = null;
            return;
        }

        SpanExporter exporter = testExporter != null
                ? testExporter
                : OtlpHttpSpanExporter.builder()
                        .setEndpoint(endpoint(langfuse.getBaseUrl()))
                        .addHeader("Authorization", basic(langfuse.getPublicKey(), langfuse.getSecretKey()))
                        .addHeader("x-langfuse-ingestion-version", "4")
                        .setTimeout(Duration.ofSeconds(Math.max(1, langfuse.getTimeoutSeconds())))
                        .build();
        SpanProcessor processor = testExporter != null
                ? SimpleSpanProcessor.create(exporter)
                : BatchSpanProcessor.builder(exporter)
                        .setMaxQueueSize(Math.max(1, langfuse.getQueueCapacity()))
                        .setScheduleDelay(Duration.ofSeconds(1))
                        .setExporterTimeout(Duration.ofSeconds(Math.max(1, langfuse.getTimeoutSeconds())))
                        .build();
        this.provider = SdkTracerProvider.builder()
                .setResource(Resource.getDefault().merge(Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), "ai-runtime-governance",
                        AttributeKey.stringKey("service.version"), "0.1.0"))))
                .addSpanProcessor(processor)
                .build();
        this.tracer = provider.get("local.governance.gateway");
        log.info("Langfuse OTLP export enabled for {}", endpoint(langfuse.getBaseUrl()));
    }

    @Override
    public void export(Observation observation) {
        if (tracer == null) {
            return;
        }
        try {
            long startedAt = Math.max(0, System.currentTimeMillis() - observation.durationMs());
            Span span = tracer.spanBuilder("chat " + observation.model())
                    .setSpanKind(SpanKind.SERVER)
                    .setStartTimestamp(startedAt, TimeUnit.MILLISECONDS)
                    .startSpan();
            try {
                try (io.opentelemetry.context.Scope ignored = span.makeCurrent()) {
                    traceAttributes(span, observation);
                    if (observation.guardOutcome() != null) {
                        guardrailSpan(observation);
                    }
                    span.setStatus(
                            "completed".equals(observation.status())
                                    ? StatusCode.OK
                                    : StatusCode.ERROR,
                            observation.status());
                }
            } finally {
                span.end();
            }
        } catch (RuntimeException exception) {
            log.warn("could not build Langfuse observation", exception);
        }
    }

    private void traceAttributes(Span span, Observation observation) {
        GovernanceProperties.LangfuseProperties langfuse = properties.getLangfuse();
        span.setAttribute("langfuse.trace.name", "gateway " + observation.model());
        span.setAttribute("langfuse.user.id", observation.scopeId().toString());
        if (observation.sessionId() != null) {
            span.setAttribute("langfuse.session.id", observation.sessionId());
        }
        span.setAttribute("langfuse.release", "0.1.0");
        span.setAttribute(TAGS, List.of(
                "gateway",
                "model:" + observation.model(),
                "status:" + observation.status()));
        span.setAttribute("langfuse.trace.metadata.trace_id", observation.traceId().toString());
        if (observation.guardOutcome() != null) {
            span.setAttribute("langfuse.trace.metadata.guard_outcome", observation.guardOutcome());
        }
        span.setAttribute("langfuse.trace.metadata.policy_id", GatewayGuardrail.POLICY_ID);
        span.setAttribute("langfuse.trace.metadata.policy_version", GatewayGuardrail.POLICY_VERSION);

        span.setAttribute("langfuse.observation.type", "generation");
        span.setAttribute("langfuse.observation.model.name", observation.model());
        span.setAttribute("langfuse.observation.level",
                "completed".equals(observation.status()) ? "DEFAULT" : "ERROR");
        span.setAttribute("langfuse.observation.status_message", observation.status());

        if (langfuse.isCaptureInput() && observation.prompt() != null) {
            span.setAttribute("langfuse.observation.input",
                    truncate(input(observation.prompt()), properties.getGateway().getMaxCaptureBytes()));
        }
        if (langfuse.isCaptureOutput() && observation.output() != null) {
            span.setAttribute("langfuse.observation.output",
                    truncate(observation.output(), properties.getGateway().getMaxCaptureBytes()));
        }
        if (observation.inputTokens() != null || observation.outputTokens() != null) {
            span.setAttribute("langfuse.observation.usage_details", usage(observation));
        }
        if (observation.finishReason() != null) {
            span.setAttribute(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons"),
                    List.of(observation.finishReason()));
        }
        span.setAttribute("gen_ai.operation.name", "chat");
        span.setAttribute("gen_ai.provider.name", "openai");
        span.setAttribute("gen_ai.request.model", observation.model());
        span.setAttribute("gen_ai.response.model", observation.model());
        if (observation.inputTokens() != null) {
            span.setAttribute("gen_ai.usage.input_tokens", observation.inputTokens().longValue());
        }
        if (observation.outputTokens() != null) {
            span.setAttribute("gen_ai.usage.output_tokens", observation.outputTokens().longValue());
        }
    }

    private void guardrailSpan(Observation observation) {
        Span guard = tracer.spanBuilder("prompt-guard")
                .setParent(Context.current())
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        try {
            guard.setAttribute("langfuse.observation.type", "guardrail");
            guard.setAttribute("langfuse.observation.output", observation.guardOutcome());
            guard.setAttribute("langfuse.observation.level", "DEFAULT");
        } finally {
            guard.end();
        }
    }

    private String input(String prompt) {
        try {
            return objectMapper.writeValueAsString(List.of(Map.of("role", "user", "content", prompt)));
        } catch (Exception exception) {
            return prompt;
        }
    }

    private String usage(Observation observation) {
        Map<String, Object> usage = new LinkedHashMap<>();
        if (observation.inputTokens() != null) {
            usage.put("input", observation.inputTokens());
        }
        if (observation.outputTokens() != null) {
            usage.put("output", observation.outputTokens());
        }
        try {
            return objectMapper.writeValueAsString(usage);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private static String truncate(String value, int limit) {
        if (value == null || limit <= 0 || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "\n[truncated]";
    }

    private static String endpoint(String baseUrl) {
        String value = baseUrl == null ? "https://cloud.langfuse.com" : baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.endsWith("/api/public/otel")
                ? value + "/v1/traces"
                : value + "/api/public/otel/v1/traces";
    }

    private static String basic(String publicKey, String secretKey) {
        String value = publicKey + ":" + secretKey;
        return "Basic " + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @PreDestroy
    public void shutdown() {
        if (provider != null) {
            provider.shutdown().join(5, TimeUnit.SECONDS);
        }
    }
}
