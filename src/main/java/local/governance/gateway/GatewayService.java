package local.governance.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import local.governance.api.APIException;
import local.governance.config.GovernanceProperties;
import local.governance.gateway.GatewayConversationRepository.Row;
import local.governance.gateway.GatewayCredentialService.Principal;
import local.governance.gateway.GatewayRequestParser.Parsed;
import local.governance.guard.GuardOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    public record ProxyResponse(int status, HttpHeaders headers, StreamingResponseBody body) {}

    private record Prepared(
            UUID traceId,
            UUID scopeId,
            String conversationKey,
            String model,
            byte[] body,
            boolean stream,
            String prompt,
            String guardOutcome) {}

    private final GovernanceProperties properties;
    private final GatewayRequestParser parser;
    private final GatewayGuardrail guardrail;
    private final GatewayConversationService conversations;
    private final GatewayTurnRecorder turnRecorder;
    private final GatewayTraceRecorder traces;
    private final GatewayUpstreamClient upstream;
    private final GatewayResponseCapture capture;
    private final GatewayRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final Optional<GatewayObservationExporter> observationExporter;

    public GatewayService(
            GovernanceProperties properties,
            GatewayRequestParser parser,
            GatewayGuardrail guardrail,
            GatewayConversationService conversations,
            GatewayTurnRecorder turnRecorder,
            GatewayTraceRecorder traces,
            GatewayUpstreamClient upstream,
            GatewayResponseCapture capture,
            GatewayRateLimiter rateLimiter,
            ObjectMapper objectMapper,
            Optional<GatewayObservationExporter> observationExporter) {
        this.properties = properties;
        this.parser = parser;
        this.guardrail = guardrail;
        this.conversations = conversations;
        this.turnRecorder = turnRecorder;
        this.traces = traces;
        this.upstream = upstream;
        this.capture = capture;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.observationExporter = observationExporter;
    }

    public ProxyResponse exchange(Principal principal, byte[] rawBody, String clientConversationId) {
        GovernanceProperties.GatewayProperties gateway = properties.getGateway();
        if (!gateway.isEnabled()) {
            throw new APIException("GATEWAY_DISABLED", "The model gateway is disabled.");
        }
        if (rawBody != null && rawBody.length > gateway.getMaxRequestBytes()) {
            throw new APIException("REQUEST_TOO_LARGE", "The request body is too large.");
        }
        if (!rateLimiter.allow(principal.credentialId())) {
            throw new APIException(
                    "RATE_LIMITED",
                    "Too many gateway requests. Try again shortly.",
                    60);
        }

        Parsed parsed = parser.parse(rawBody);
        if (!gateway.modelList().isEmpty() && !gateway.modelList().contains(parsed.model())) {
            throw new APIException(
                    "MODEL_NOT_ALLOWED",
                    "This model is not enabled for the gateway.");
        }

        Prepared prepared = prepare(principal, parsed, rawBody, clientConversationId);

        if (!rateLimiter.acquireUpstream()) {
            throw new APIException(
                    "RATE_LIMITED",
                    "The gateway is busy. Try again shortly.",
                    1);
        }
        HttpResponse<InputStream> response;
        try {
            response = upstream.sendChatCompletion(prepared.body(), prepared.stream());
        } catch (RuntimeException exception) {
            rateLimiter.releaseUpstream();
            try {
                traces.dispatchFailed(prepared.scopeId(), prepared.traceId());
            } catch (RuntimeException traceFailure) {
                log.warn("gateway dispatch failure could not be recorded", traceFailure);
            }
            throw exception;
        }
        return proxy(prepared, response);
    }

    private Prepared prepare(
            Principal principal,
            Parsed parsed,
            byte[] rawBody,
            String clientConversationId) {
        if (parsed.mode() == GatewayRequestParser.Mode.NEW_TURN) {
            return prepareNewTurn(principal, parsed, rawBody, clientConversationId);
        }
        return prepareContinuation(principal, parsed, rawBody, clientConversationId);
    }

    private Prepared prepareNewTurn(
            Principal principal,
            Parsed parsed,
            byte[] rawBody,
            String clientConversationId) {
        GatewayGuardrail.Decision decision = guardrail.evaluate(parsed.userText());
        UUID traceId = UUID.randomUUID();

        if (decision.failed()) {
            traces.blocked(
                    principal.scopeId(),
                    traceId,
                    parsed.model(),
                    null,
                    GatewayGuardrail.POLICY_ID,
                    GatewayGuardrail.POLICY_VERSION,
                    List.of(),
                    true);
            throw new APIException(
                    "GUARD_EVALUATION_FAILED",
                    "The prompt guard could not evaluate this message, so it was not forwarded.");
        }
        if (decision.outcome() == GuardOutcome.DENY) {
            traces.blocked(
                    principal.scopeId(),
                    traceId,
                    parsed.model(),
                    GuardOutcome.DENY,
                    GatewayGuardrail.POLICY_ID,
                    GatewayGuardrail.POLICY_VERSION,
                    decision.ruleIds(),
                    false);
            throw new APIException("GUARD_DENIED", "The governance guard denied this prompt.");
        }
        if (decision.outcome() == GuardOutcome.REQUIRE_APPROVAL) {
            traces.blocked(
                    principal.scopeId(),
                    traceId,
                    parsed.model(),
                    GuardOutcome.REQUIRE_APPROVAL,
                    GatewayGuardrail.POLICY_ID,
                    GatewayGuardrail.POLICY_VERSION,
                    decision.ruleIds(),
                    false);
            throw new APIException(
                    "GUARD_APPROVAL_REQUIRED",
                    "This prompt requires the submitter's approval before it can be forwarded.");
        }

        byte[] forwardBody = rewrite(
                parsed.body(), parsed.userMessageIndex(), decision.message(), rawBody);
        String conversationKey = conversations.key(
                principal.scopeId(), parsed.model(), parsed.canonicalPrefix(), clientConversationId);
        turnRecorder.recordGuardedTurn(
                principal.scopeId(),
                traceId,
                parsed.model(),
                decision.outcome(),
                GatewayGuardrail.POLICY_ID,
                GatewayGuardrail.POLICY_VERSION,
                decision.ruleIds(),
                conversationKey,
                conversations.messageHash(parsed.userText()));
        return new Prepared(
                traceId,
                principal.scopeId(),
                conversationKey,
                parsed.model(),
                forwardBody,
                parsed.stream(),
                decision.outcome() == GuardOutcome.REDACT ? decision.message() : parsed.userText(),
                decision.outcome().name().toLowerCase(java.util.Locale.ROOT));
    }

    private Prepared prepareContinuation(
            Principal principal,
            Parsed parsed,
            byte[] rawBody,
            String clientConversationId) {
        String conversationKey = conversations.key(
                principal.scopeId(), parsed.model(), parsed.canonicalPrefix(), clientConversationId);
        Optional<Row> found = conversations.find(principal.scopeId(), conversationKey);
        if (found.isEmpty()) {
            if (properties.getGateway().isAllowUnguardedContinuation()) {
                UUID traceId = UUID.randomUUID();
                turnRecorder.recordBypassedTurn(principal.scopeId(), traceId, parsed.model());
                return new Prepared(
                        traceId,
                        principal.scopeId(),
                        conversationKey,
                        parsed.model(),
                        rawBody,
                        parsed.stream(),
                        null,
                        null);
            }
            throw new APIException(
                    "GUARD_CONTEXT_UNKNOWN",
                    "The gateway could not match this continuation to a guarded human turn.");
        }

        Row conversation = found.get();
        conversations.record(
                principal.scopeId(),
                conversation.traceId(),
                conversationKey,
                conversation.guardedMessageHash(),
                conversation.guardOutcome());
        byte[] forwardBody = rewrite(parsed.body(), -1, null, rawBody);
        return new Prepared(
                conversation.traceId(),
                principal.scopeId(),
                conversationKey,
                parsed.model(),
                forwardBody,
                parsed.stream(),
                null,
                conversation.guardOutcome());
    }

    private ProxyResponse proxy(Prepared prepared, HttpResponse<InputStream> response) {
        int status = response.statusCode();
        String contentType = response.headers().firstValue("Content-Type")
                .orElse(prepared.stream() ? "text/event-stream" : "application/json");
        HttpHeaders headers = responseHeaders(response, contentType, prepared.stream());
        StreamingResponseBody body = output -> write(prepared, response, contentType, output);
        return new ProxyResponse(status, headers, body);
    }

    private void write(
            Prepared prepared,
            HttpResponse<InputStream> response,
            String contentType,
            java.io.OutputStream output) throws IOException {
        int maxCapture = Math.max(0, properties.getGateway().getMaxCaptureBytes());
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        boolean truncated = false;
        String status = response.statusCode() >= 400 ? "failed" : "completed";
        long started = System.nanoTime();

        try (InputStream input = response.body()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                output.flush();
                if (captured.size() >= maxCapture) {
                    truncated = true;
                } else {
                    int allowed = Math.min(read, maxCapture - captured.size());
                    captured.write(buffer, 0, allowed);
                    if (allowed < read) {
                        truncated = true;
                    }
                }
            }
        } catch (IOException exception) {
            status = "canceled";
        } finally {
            rateLimiter.releaseUpstream();
        }

        GatewayResponseCapture.Capture result =
                capture.capture(captured.toByteArray(), contentType, truncated);
        long durationMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
        String finalStatus = status;
        try {
            traces.complete(
                    prepared.scopeId(),
                    prepared.traceId(),
                    prepared.model(),
                    finalStatus,
                    result.finishReason(),
                    (int) Math.min(Integer.MAX_VALUE, durationMs));
        } catch (RuntimeException exception) {
            log.warn("gateway trace completion could not be recorded", exception);
        }
        observationExporter.ifPresent(exporter -> {
            try {
                exporter.export(new GatewayObservationExporter.Observation(
                        prepared.scopeId(),
                        prepared.traceId(),
                        prepared.conversationKey(),
                        prepared.model(),
                        prepared.prompt(),
                        result.output(),
                        finalStatus,
                        result.inputTokens(),
                        result.outputTokens(),
                        result.finishReason(),
                        durationMs,
                        prepared.guardOutcome()));
            } catch (RuntimeException exception) {
                log.warn("gateway observation export failed", exception);
            }
        });
    }

    private HttpHeaders responseHeaders(
            HttpResponse<InputStream> response,
            String contentType,
            boolean stream) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, contentType);
        copy(response, headers, "Cache-Control");
        copy(response, headers, "X-Request-Id");
        copy(response, headers, "OpenAI-Processing-Ms");
        copy(response, headers, "OpenAI-Version");
        copy(response, headers, "Retry-After");
        if (!headers.containsHeader(HttpHeaders.CACHE_CONTROL)) {
            headers.set(HttpHeaders.CACHE_CONTROL, stream ? "no-cache" : "no-store");
        }
        if (stream) {
            headers.set("X-Accel-Buffering", "no");
            headers.set(HttpHeaders.CONNECTION, "keep-alive");
        }
        return headers;
    }

    private static void copy(HttpResponse<?> response, HttpHeaders headers, String name) {
        response.headers().firstValue(name).ifPresent(value -> headers.set(name, value));
    }

    private byte[] rewrite(
            JsonNode body,
            int currentIndex,
            String currentText,
            byte[] rawBody) {
        ArrayNode messages = (ArrayNode) body.get("messages");
        boolean changed = false;
        if (currentIndex >= 0 && currentText != null) {
            JsonNode existing = messages.get(currentIndex).get("content");
            if (existing == null || !existing.isTextual() || !currentText.equals(existing.asText())) {
                ((ObjectNode) messages.get(currentIndex)).put("content", currentText);
                changed = true;
            }
        }
        for (int index = 0; index < messages.size(); index += 1) {
            if (index == currentIndex) {
                continue;
            }
            JsonNode message = messages.get(index);
            JsonNode content = message.get("content");
            if (!"user".equals(text(message.get("role"))) || content == null || !content.isTextual()) {
                continue;
            }
            GatewayGuardrail.Decision decision = guardrail.evaluate(content.asText());
            if (decision.outcome() == GuardOutcome.REDACT
                    && !decision.message().equals(content.asText())) {
                ((ObjectNode) message).put("content", decision.message());
                changed = true;
            }
        }
        return changed ? serialise(body) : rawBody;
    }

    private byte[] serialise(JsonNode body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (Exception exception) {
            throw new APIException(
                    "PROTOCOL_UNSUPPORTED",
                    "The request could not be rewritten after guard evaluation.");
        }
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }
}
