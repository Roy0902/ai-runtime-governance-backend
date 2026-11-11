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
import io.opentelemetry.sdk.trace.IdGenerator;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import local.governance.config.GovernanceProperties;
import local.governance.provider.ProviderCredentialService;
import local.governance.provider.ProviderKind;
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
    private final ProviderCredentialService credentials;
    private final SpanExporter testExporter;
    private final Tracer globalTracer;
    private final boolean testMode;
    private final Map<String, TracerHandle> ownerTracers = new ConcurrentHashMap<>();
    private final List<SdkTracerProvider> providers = new CopyOnWriteArrayList<>();

    private record TracerHandle(SdkTracerProvider provider, Tracer tracer) {}

    @Autowired
    public LangfuseObservationExporter(
            GovernanceProperties properties,
            ObjectMapper objectMapper,
            ProviderCredentialService credentials) {
        this(properties, objectMapper, null, credentials);
    }

    LangfuseObservationExporter(
            GovernanceProperties properties,
            ObjectMapper objectMapper,
            SpanExporter testExporter) {
        this(properties, objectMapper, testExporter, null);
    }

    LangfuseObservationExporter(GovernanceProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, null, null);
    }

    LangfuseObservationExporter(
            GovernanceProperties properties,
            ObjectMapper objectMapper,
            SpanExporter testExporter,
            ProviderCredentialService credentials) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.credentials = credentials;
        this.testExporter = testExporter;
        this.testMode = testExporter != null;
        GovernanceProperties.LangfuseProperties langfuse = properties.getLangfuse();
        boolean configured = testExporter != null
                || (!isBlank(langfuse.getPublicKey()) && !isBlank(langfuse.getSecretKey()));
        if (!configured) {
            log.warn("Langfuse has no deployment keys; per-owner credentials are used when present");
            this.globalTracer = null;
            return;
        }
        TracerHandle handle = build(
                testExporter,
                langfuse.getBaseUrl(),
                langfuse.getPublicKey(),
                langfuse.getSecretKey());
        this.globalTracer = handle.tracer();
        providers.add(handle.provider());
        log.info("Langfuse OTLP export enabled for {}", endpoint(langfuse.getBaseUrl()));
    }

    @Override
    public String export(Observation observation) {
        Tracer tracer = tracerFor(observation);
        if (tracer == null) {
            return null;
        }
        try {
            long startedAt = Math.max(0, System.currentTimeMillis() - observation.durationMs());
            return TraceIdOverrideGenerator.withTraceId(
                    observation.traceId(), () -> exportSpan(tracer, observation, startedAt));
        } catch (RuntimeException exception) {
            log.warn("could not build Langfuse observation", exception);
            return null;
        }
    }

    private String exportSpan(Tracer tracer, Observation observation, long startedAt) {
        Span span = tracer.spanBuilder("chat " + observation.model())
                .setParent(Context.root())
                .setSpanKind(SpanKind.SERVER)
                .setStartTimestamp(startedAt, TimeUnit.MILLISECONDS)
                .startSpan();
        String observationId = span.getSpanContext().getSpanId();
        try {
            try (io.opentelemetry.context.Scope ignored = span.makeCurrent()) {
                traceAttributes(span, observation);
                if (observation.guardOutcome() != null) {
                    guardrailSpan(tracer, observation);
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
        return observationId;
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
        if (observation.inputTokens() != null
                || observation.outputTokens() != null
                || observation.cachedInputTokens() != null
                || observation.reasoningOutputTokens() != null
                || observation.totalTokens() != null) {
            span.setAttribute("langfuse.observation.usage_details", usage(observation));
        }
        if (observation.totalCostUsd() != null) {
            span.setAttribute("langfuse.observation.cost_details", cost(observation));
            span.setAttribute("gen_ai.usage.cost", observation.totalCostUsd().doubleValue());
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
        if (observation.cachedInputTokens() != null) {
            span.setAttribute("gen_ai.usage.cache_read.input_tokens",
                    observation.cachedInputTokens().longValue());
        }
        if (observation.reasoningOutputTokens() != null) {
            span.setAttribute("gen_ai.usage.reasoning.output_tokens",
                    observation.reasoningOutputTokens().longValue());
        }
        if (observation.totalTokens() != null) {
            span.setAttribute("gen_ai.usage.total_tokens", observation.totalTokens().longValue());
        }
    }

    private Tracer tracerFor(Observation observation) {
        if (credentials == null || observation.accountId() == null) {
            return globalTracer;
        }
        try {
            ProviderCredentialService.Resolved value = null;
            String ownerKey = null;
            if (observation.groupId() != null) {
                Optional<ProviderCredentialService.Resolved> groupCredential =
                        credentials.resolveForGroup(observation.groupId(), ProviderKind.LANGFUSE);
                if (groupCredential.isPresent() && !isBlank(groupCredential.get().accessKey())) {
                    value = groupCredential.get();
                    ownerKey = "g:" + observation.groupId();
                }
            }
            if (value == null) {
                Optional<ProviderCredentialService.Resolved> personalCredential =
                        credentials.resolve(observation.accountId(), ProviderKind.LANGFUSE);
                if (personalCredential.isPresent() && !isBlank(personalCredential.get().accessKey())) {
                    value = personalCredential.get();
                    ownerKey = "u:" + observation.accountId();
                }
            }
            if (value == null) {
                return globalTracer;
            }
            String key = ownerKey + ":" + fingerprint(value.accessKey(), value.secret());
            TracerHandle cached = ownerTracers.get(key);
            if (cached != null) {
                return cached.tracer();
            }
            TracerHandle handle = build(
                    testMode ? testExporter : null,
                    properties.getLangfuse().getBaseUrl(),
                    value.accessKey(),
                    value.secret());
            TracerHandle existing = ownerTracers.putIfAbsent(key, handle);
            if (existing != null) {
                handle.provider().shutdown();
                return existing.tracer();
            }
            providers.add(handle.provider());
            return handle.tracer();
        } catch (RuntimeException exception) {
            log.warn("could not resolve Langfuse credentials for the request; using deployment keys", exception);
            return globalTracer;
        }
    }

    private TracerHandle build(
            SpanExporter exporterOverride,
            String baseUrl,
            String publicKey,
            String secretKey) {
        GovernanceProperties.LangfuseProperties langfuse = properties.getLangfuse();
        SpanExporter exporter = exporterOverride != null
                ? exporterOverride
                : OtlpHttpSpanExporter.builder()
                        .setEndpoint(endpoint(baseUrl))
                        .addHeader("Authorization", basic(publicKey, secretKey))
                        .addHeader("x-langfuse-ingestion-version", "4")
                        .setTimeout(Duration.ofSeconds(Math.max(1, langfuse.getTimeoutSeconds())))
                        .build();
        SpanProcessor processor = exporterOverride != null
                ? SimpleSpanProcessor.create(exporter)
                : BatchSpanProcessor.builder(exporter)
                        .setMaxQueueSize(Math.max(1, langfuse.getQueueCapacity()))
                        .setScheduleDelay(Duration.ofSeconds(1))
                        .setExporterTimeout(Duration.ofSeconds(Math.max(1, langfuse.getTimeoutSeconds())))
                        .build();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setResource(Resource.getDefault().merge(Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), "ai-runtime-governance",
                        AttributeKey.stringKey("service.version"), "0.1.0"))))
                .setIdGenerator(new TraceIdOverrideGenerator())
                .addSpanProcessor(processor)
                .build();
        return new TracerHandle(provider, provider.get("local.governance.gateway"));
    }

    private void guardrailSpan(Tracer tracer, Observation observation) {
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
        if (observation.cachedInputTokens() != null) {
            usage.put("cached_input", observation.cachedInputTokens());
        }
        if (observation.reasoningOutputTokens() != null) {
            usage.put("reasoning", observation.reasoningOutputTokens());
        }
        if (observation.totalTokens() != null) {
            usage.put("total", observation.totalTokens());
        }
        try {
            return objectMapper.writeValueAsString(usage);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private String cost(Observation observation) {
        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("total", observation.totalCostUsd());
        if (observation.providerReportedCostUsd() != null) {
            cost.put("provider_reported", observation.providerReportedCostUsd());
        }
        try {
            return objectMapper.writeValueAsString(cost);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private static String traceIdHex(UUID traceId) {
        return traceId.toString().replace("-", "");
    }

    /**
     * Forces the OTel trace ID of the next root span to the local trace UUID so
     * every generation of one conversation is grouped under one Langfuse trace.
     * The override is thread-local because export runs on the request thread.
     */
    private static final class TraceIdOverrideGenerator implements IdGenerator {

        private static final ThreadLocal<String> OVERRIDE = new ThreadLocal<>();
        private static final IdGenerator RANDOM = IdGenerator.random();

        private static <T> T withTraceId(UUID traceId, Supplier<T> action) {
            String previous = OVERRIDE.get();
            OVERRIDE.set(traceIdHex(traceId));
            try {
                return action.get();
            } finally {
                if (previous == null) {
                    OVERRIDE.remove();
                } else {
                    OVERRIDE.set(previous);
                }
            }
        }

        @Override
        public String generateSpanId() {
            return RANDOM.generateSpanId();
        }

        @Override
        public String generateTraceId() {
            String override = OVERRIDE.get();
            return override == null ? RANDOM.generateTraceId() : override;
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

    private static String fingerprint(String accessKey, String secret) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((accessKey + "\u0000" + secret).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @PreDestroy
    public void shutdown() {
        for (SdkTracerProvider tracerProvider : providers) {
            tracerProvider.shutdown().join(5, TimeUnit.SECONDS);
        }
        providers.clear();
        ownerTracers.clear();
    }
}
