package local.governance.gateway;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import local.governance.api.APIException;
import local.governance.config.GovernanceProperties;
import local.governance.gateway.GatewayConversationRepository.Row;
import local.governance.gateway.GatewayCredentialService.Principal;
import local.governance.guard.GuardOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GatewayServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    private final UUID scopeId = UUID.randomUUID();
    private final UUID credentialId = UUID.randomUUID();
    private final Principal principal = new Principal(credentialId, UUID.randomUUID(), scopeId);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private GovernanceProperties properties;
    private GatewayGuardrail guardrail;
    private GatewayConversationService conversations;
    private GatewayTurnRecorder turnRecorder;
    private GatewayTraceRecorder traces;
    private GatewayUpstreamClient upstream;
    private GatewayPricing pricing;
    private ModelUsageRecorder usage;
    private GatewayOutputPersistence outputPersistence;
    private GatewayService service;

    @BeforeEach
    void setUp() {
        properties = new GovernanceProperties();
        properties.getGateway().setRequestsPerMinute(1_000);
        properties.getGateway().setMaxConcurrentUpstream(4);
        guardrail = mock(GatewayGuardrail.class);
        conversations = mock(GatewayConversationService.class);
        turnRecorder = mock(GatewayTurnRecorder.class);
        traces = mock(GatewayTraceRecorder.class);
        upstream = mock(GatewayUpstreamClient.class);
        pricing = mock(GatewayPricing.class);
        usage = mock(ModelUsageRecorder.class);
        outputPersistence = mock(GatewayOutputPersistence.class);
        when(pricing.calculate(any(), any(), any(), any(), any()))
                .thenReturn(new GatewayPricing.Cost(null, null, null, null, "USD"));
        when(conversations.key(any(), anyString(), anyString(), any()))
                .thenReturn("conversation-key");
        when(conversations.messageHash(anyString())).thenReturn("message-hash");
        service = new GatewayService(
                properties,
                new GatewayRequestParser(objectMapper),
                guardrail,
                conversations,
                turnRecorder,
                traces,
                upstream,
                new GatewayResponseCapture(objectMapper),
                pricing,
                usage,
                outputPersistence,
                new GatewayRateLimiter(properties, Clock.fixed(NOW, ZoneOffset.UTC)),
                objectMapper,
                Optional.empty());
    }

    @Test
    void forwardsAnAllowedTurnAndRecordsCompletion() throws Exception {
        byte[] request = bytes("""
                {"model":"gpt-4o","messages":[{"role":"user","content":"hello"}]}
                """);
        String responseBody = """
                {"choices":[{"message":{"content":"hi"},"finish_reason":"stop"}],
                "usage":{"prompt_tokens":3,"completion_tokens":1}}
                """;
        when(guardrail.evaluate("hello"))
                .thenReturn(new GatewayGuardrail.Decision(GuardOutcome.ALLOW, "hello", List.of(), List.of()));
        when(upstream.sendChatCompletion(
                    any(), eq(false), any()))
                .thenReturn(httpResponse(200, "application/json", responseBody));

        GatewayService.ProxyResponse response = service.exchange(principal, request, null);

        assertEquals(200, response.status());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.body().writeTo(output);
        assertEquals(responseBody, output.toString(StandardCharsets.UTF_8));
        runOutputPersistence();
        verify(turnRecorder).recordGuardedTurn(
                eq(scopeId),
                any(UUID.class),
                eq("gpt-4o"),
                eq(GuardOutcome.ALLOW),
                eq(GatewayGuardrail.POLICY_ID),
                eq(GatewayGuardrail.POLICY_VERSION),
                eq(List.of()),
                eq("conversation-key"),
                eq("message-hash"));
        verify(traces).complete(
                eq(scopeId), any(UUID.class), eq("gpt-4o"), eq("completed"), eq("stop"), anyInt());

        ArgumentCaptor<ModelUsageRecorder.Usage> recorded =
                ArgumentCaptor.forClass(ModelUsageRecorder.Usage.class);
        verify(usage).record(recorded.capture());
        assertEquals(3L, recorded.getValue().inputTokens());
        assertEquals(1L, recorded.getValue().outputTokens());
        assertEquals("stop", recorded.getValue().finishReasons().getFirst());
        assertEquals("completed", recorded.getValue().outcome());
    }

    @Test
    void recordsTheLangfuseObservationIdOnTheUsageRow() throws Exception {
        GatewayObservationExporter exporter = mock(GatewayObservationExporter.class);
        when(exporter.export(any())).thenReturn("0123456789abcdef");
        GatewayService withExporter = serviceWithExporter(exporter);
        when(guardrail.evaluate("hello"))
                .thenReturn(new GatewayGuardrail.Decision(GuardOutcome.ALLOW, "hello", List.of(), List.of()));
        when(upstream.sendChatCompletion(any(), eq(false), any()))
                .thenReturn(httpResponse(
                        200,
                        "application/json",
                        "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));

        withExporter.exchange(principal, bytes(
                        "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"),
                        null)
                .body()
                .writeTo(new ByteArrayOutputStream());
        runOutputPersistence();

        ArgumentCaptor<ModelUsageRecorder.Usage> recorded =
                ArgumentCaptor.forClass(ModelUsageRecorder.Usage.class);
        verify(usage).record(recorded.capture());
        assertEquals("0123456789abcdef", recorded.getValue().langfuseObservationId());

        ArgumentCaptor<GatewayObservationExporter.Observation> observation =
                ArgumentCaptor.forClass(GatewayObservationExporter.Observation.class);
        verify(exporter).export(observation.capture());
        assertEquals(recorded.getValue().traceId(), observation.getValue().traceId());
    }

    @Test
    void rewritesTheSubmittedMessageBeforeForwarding() throws Exception {
        byte[] request = bytes("""
                {"model":"gpt-4o","messages":[{"role":"user","content":"email person@example.test"}]}
                """);
        when(guardrail.evaluate("email person@example.test")).thenReturn(
                new GatewayGuardrail.Decision(
                        GuardOutcome.REDACT,
                        "email [REDACTED_EMAIL]",
                        List.of("FX-EMAIL"),
                        List.of()));
        ArgumentCaptor<byte[]> forwarded = ArgumentCaptor.forClass(byte[].class);
        when(upstream.sendChatCompletion(
                    forwarded.capture(), eq(false), any()))
                .thenReturn(httpResponse(200, "application/json", "{\"choices\":[]}"));

        service.exchange(principal, request, null).body().writeTo(new ByteArrayOutputStream());

        JsonNode sent = objectMapper.readTree(forwarded.getValue());
        assertEquals(
                "email [REDACTED_EMAIL]",
                sent.path("messages").path(0).path("content").asText());
        assertTrue(!new String(forwarded.getValue(), StandardCharsets.UTF_8).contains("person@example.test"));
    }

    @Test
    void neverForwardsADeniedTurn() {
        when(guardrail.evaluate("bad")).thenReturn(
                new GatewayGuardrail.Decision(GuardOutcome.DENY, "bad", List.of("FX-DENY"), List.of()));

        APIException exception = assertThrows(
                APIException.class,
                () -> service.exchange(principal, bytes(
                        "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"bad\"}]}"),
                        null));

        assertEquals("GUARD_DENIED", exception.code());
        verify(traces).blocked(
                eq(scopeId),
                any(UUID.class),
                eq("gpt-4o"),
                eq(GuardOutcome.DENY),
                eq(GatewayGuardrail.POLICY_ID),
                eq(GatewayGuardrail.POLICY_VERSION),
                eq(List.of("FX-DENY")),
                eq(false));
        verifyNoInteractions(upstream, turnRecorder);
    }

    @Test
    void neverForwardsAPromptThatNeedsApprovalInThisSlice() {
        when(guardrail.evaluate("inject")).thenReturn(
                new GatewayGuardrail.Decision(
                        GuardOutcome.REQUIRE_APPROVAL, "inject", List.of("FX-INJECTION"), List.of()));

        APIException exception = assertThrows(
                APIException.class,
                () -> service.exchange(principal, bytes(
                        "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"inject\"}]}"),
                        null));

        assertEquals("GUARD_APPROVAL_REQUIRED", exception.code());
        verifyNoInteractions(upstream);
    }

    @Test
    void neverForwardsWhenTheGuardCannotEvaluate() {
        when(guardrail.evaluate("boom")).thenReturn(
                new GatewayGuardrail.Decision(null, "boom", List.of(), List.of("INVALID_TEXT")));

        APIException exception = assertThrows(
                APIException.class,
                () -> service.exchange(principal, bytes(
                        "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"boom\"}]}"),
                        null));

        assertEquals("GUARD_EVALUATION_FAILED", exception.code());
        verify(traces).blocked(
                eq(scopeId), any(UUID.class), eq("gpt-4o"), isNull(), anyString(), anyString(),
                eq(List.of()), eq(true));
        verifyNoInteractions(upstream);
    }

    @Test
    void rejectsAContinuationThatIsNotLinkedToAGuardedTurn() {
        when(conversations.find(scopeId, "conversation-key")).thenReturn(Optional.empty());

        APIException exception = assertThrows(
                APIException.class,
                () -> service.exchange(principal, bytes("""
                        {"model":"gpt-4o","messages":[
                          {"role":"user","content":"hello"},
                          {"role":"tool","tool_call_id":"call-1","content":"ok"}
                        ]}
                        """), null));

        assertEquals("GUARD_CONTEXT_UNKNOWN", exception.code());
        verifyNoInteractions(upstream, turnRecorder);
    }

    @Test
    void sanitizesHistoryOnAContinuationOfARedactedTurn() throws Exception {
        UUID traceId = UUID.randomUUID();
        when(conversations.find(scopeId, "conversation-key")).thenReturn(Optional.of(new Row(
                "conversation-key",
                scopeId,
                traceId,
                "message-hash",
                "redact",
                true,
                NOW,
                NOW.plusSeconds(600))));
        when(guardrail.evaluate("email person@example.test")).thenReturn(
                new GatewayGuardrail.Decision(
                        GuardOutcome.REDACT,
                        "email [REDACTED_EMAIL]",
                        List.of("FX-EMAIL"),
                        List.of()));
        ArgumentCaptor<byte[]> forwarded = ArgumentCaptor.forClass(byte[].class);
        when(upstream.sendChatCompletion(
                    forwarded.capture(), eq(false), any()))
                .thenReturn(httpResponse(200, "application/json", "{\"choices\":[]}"));

        service.exchange(principal, bytes("""
                {"model":"gpt-4o","messages":[
                  {"role":"user","content":"email person@example.test"},
                  {"role":"assistant","content":null,
                   "tool_calls":[{"id":"call-1","type":"function",
                     "function":{"name":"x","arguments":"{}"}}]},
                  {"role":"tool","tool_call_id":"call-1","content":"ok"}
                ]}
                """), null).body().writeTo(new ByteArrayOutputStream());
        runOutputPersistence();

        JsonNode sent = objectMapper.readTree(forwarded.getValue());
        assertEquals(
                "email [REDACTED_EMAIL]",
                sent.path("messages").path(0).path("content").asText());
        verify(traces).complete(
                eq(scopeId), eq(traceId), eq("gpt-4o"), eq("completed"), isNull(), anyInt());
        verify(turnRecorder, never()).recordGuardedTurn(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void preservesAnUpstreamErrorStatusAndMarksTheTraceFailed() throws Exception {
        when(guardrail.evaluate("hello"))
                .thenReturn(new GatewayGuardrail.Decision(GuardOutcome.ALLOW, "hello", List.of(), List.of()));
        when(upstream.sendChatCompletion(
                    any(), eq(false), any()))
                .thenReturn(httpResponse(429, "application/json", "{\"error\":{\"message\":\"slow down\"}}"));

        GatewayService.ProxyResponse response = service.exchange(principal, bytes(
                "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"), null);

        assertEquals(429, response.status());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.body().writeTo(output);
        runOutputPersistence();
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("slow down"));
        verify(traces).complete(
                eq(scopeId), any(UUID.class), eq("gpt-4o"), eq("failed"), isNull(), anyInt());
    }

    @Test
    void marksTheTraceFailedWhenDispatchFails() {
        when(guardrail.evaluate("hello"))
                .thenReturn(new GatewayGuardrail.Decision(GuardOutcome.ALLOW, "hello", List.of(), List.of()));
        when(upstream.sendChatCompletion(
                    any(), eq(false), any()))
                .thenThrow(new APIException("UPSTREAM_UNAVAILABLE", "down"));

        APIException exception = assertThrows(
                APIException.class,
                () -> service.exchange(principal, bytes(
                        "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"),
                        null));

        assertEquals("UPSTREAM_UNAVAILABLE", exception.code());
        verify(traces).dispatchFailed(eq(scopeId), any(UUID.class));
        ArgumentCaptor<ModelUsageRecorder.Usage> recorded =
                ArgumentCaptor.forClass(ModelUsageRecorder.Usage.class);
        verify(usage).record(recorded.capture());
        assertEquals("failed", recorded.getValue().outcome());
        assertEquals("UPSTREAM_UNAVAILABLE", recorded.getValue().errorCode());
    }

    @Test
    void injectsStreamUsageWhenConfigured() throws Exception {
        properties.getGateway().setForceStreamUsage(true);
        when(guardrail.evaluate("hello"))
                .thenReturn(new GatewayGuardrail.Decision(GuardOutcome.ALLOW, "hello", List.of(), List.of()));
        ArgumentCaptor<byte[]> forwarded = ArgumentCaptor.forClass(byte[].class);
        when(upstream.sendChatCompletion(
                    forwarded.capture(), eq(true), any()))
                .thenReturn(httpResponse(200, "text/event-stream", "data: [DONE]\n\n"));

        service.exchange(principal, bytes(
                "{\"model\":\"gpt-4o\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"),
                null).body().writeTo(new ByteArrayOutputStream());

        JsonNode sent = objectMapper.readTree(forwarded.getValue());
        assertTrue(sent.path("stream_options").path("include_usage").asBoolean());
    }

    @Test
    void forwardsTheOriginalBytesWhenNoRedactionIsNeeded() throws Exception {
        byte[] request = bytes("""
                {"model":"gpt-4o","messages":[{"role":"user","content":"hello"}]}
                """);
        when(guardrail.evaluate("hello"))
                .thenReturn(new GatewayGuardrail.Decision(GuardOutcome.ALLOW, "hello", List.of(), List.of()));
        ArgumentCaptor<byte[]> forwarded = ArgumentCaptor.forClass(byte[].class);
        when(upstream.sendChatCompletion(
                    forwarded.capture(), eq(false), any()))
                .thenReturn(httpResponse(200, "application/json", "{\"choices\":[]}"));

        service.exchange(principal, request, null).body().writeTo(new ByteArrayOutputStream());

        assertArrayEquals(request, forwarded.getValue());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private GatewayService serviceWithExporter(GatewayObservationExporter exporter) {
        return new GatewayService(
                properties,
                new GatewayRequestParser(objectMapper),
                guardrail,
                conversations,
                turnRecorder,
                traces,
                upstream,
                new GatewayResponseCapture(objectMapper),
                pricing,
                usage,
                outputPersistence,
                new GatewayRateLimiter(properties, Clock.fixed(NOW, ZoneOffset.UTC)),
                objectMapper,
                Optional.of(exporter));
    }

        private void runOutputPersistence() {
                ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
                verify(outputPersistence).submit(task.capture());
                task.getValue().run();
        }

    private static HttpResponse<InputStream> httpResponse(
            int status,
            String contentType,
            String body) {
        return new SimpleHttpResponse(status, contentType, bytes(body));
    }

    private static final class SimpleHttpResponse implements HttpResponse<InputStream> {

        private final int status;
        private final java.net.http.HttpHeaders headers;
        private final InputStream body;

        private SimpleHttpResponse(int status, String contentType, byte[] body) {
            this.status = status;
            this.headers = java.net.http.HttpHeaders.of(
                    java.util.Map.of("Content-Type", List.of(contentType)),
                    (name, value) -> true);
            this.body = new ByteArrayInputStream(body);
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public java.net.http.HttpRequest request() {
            return null;
        }

        @Override
        public java.util.Optional<HttpResponse<InputStream>> previousResponse() {
            return java.util.Optional.empty();
        }

        @Override
        public java.net.http.HttpHeaders headers() {
            return headers;
        }

        @Override
        public InputStream body() {
            return body;
        }

        @Override
        public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
            return java.util.Optional.empty();
        }

        @Override
        public java.net.URI uri() {
            return java.net.URI.create("http://upstream.test/v1/chat/completions");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
