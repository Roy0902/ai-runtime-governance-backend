package local.governance.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class GatewayResponseCaptureTest {

    private final GatewayResponseCapture capture = new GatewayResponseCapture(new ObjectMapper());

    @Test
    void capturesANonStreamingGeneration() {
        GatewayResponseCapture.Capture result = capture.capture(bytes("""
                {"choices":[{"message":{"role":"assistant","content":"hello"},
                "finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2}}
                """), "application/json", false);

        assertEquals("hello", result.output());
        assertEquals("stop", result.finishReason());
        assertEquals(3L, result.inputTokens());
        assertEquals(2L, result.outputTokens());
        assertFalse(result.toolCallObserved());
    }

    @Test
    void capturesAnSseGenerationAndToolCalls() {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"he\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"llo\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\","
                + "\"function\":{\"name\":\"weather\",\"arguments\":\"{}\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}],"
                + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":4}}\n\n"
                + "data: [DONE]\n\n";

        GatewayResponseCapture.Capture result =
                capture.capture(bytes(sse), "text/event-stream", false);

        assertTrue(result.output().startsWith("hello"));
        assertTrue(result.output().contains("[tool_calls]"));
        assertEquals("tool_calls", result.finishReason());
        assertEquals(5L, result.inputTokens());
        assertEquals(4L, result.outputTokens());
        assertTrue(result.toolCallObserved());
    }

    @Test
    void capturesProviderMetadataAndDetailedUsage() {
        GatewayResponseCapture.Capture result = capture.capture(bytes("""
                {
                  "id": "chatcmpl-1",
                  "model": "gpt-4o-2026-01-01",
                  "system_fingerprint": "fp_1",
                  "service_tier": "default",
                  "choices": [{"message": {"content": "ok"},
                    "finish_reason": "stop"}],
                  "usage": {
                    "prompt_tokens": 100,
                    "completion_tokens": 20,
                    "total_tokens": 120,
                    "prompt_tokens_details": {"cached_tokens": 40},
                    "completion_tokens_details": {"reasoning_tokens": 5},
                    "cost": 0.0123
                  }
                }
                """), "application/json", false);

        assertEquals("chatcmpl-1", result.responseId());
        assertEquals("gpt-4o-2026-01-01", result.responseModel());
        assertEquals("fp_1", result.systemFingerprint());
        assertEquals("default", result.serviceTier());
        assertEquals(120L, result.totalTokens());
        assertEquals(40L, result.cachedInputTokens());
        assertEquals(5L, result.reasoningOutputTokens());
        assertEquals(0.0123, result.providerReportedCostUsd().doubleValue());
        assertTrue(result.rawUsage().containsKey("prompt_tokens"));
    }

    @Test
    void capturesAnUpstreamErrorEnvelope() {
        GatewayResponseCapture.Capture result = capture.capture(bytes("""
                {"error":{"message":"rate limited","type":"rate_limit_error","code":"rate_limit"}}
                """), "application/json", false);

        assertEquals("rate_limit_error", result.errorType());
        assertEquals("rate_limit", result.errorCode());
        assertEquals("rate limited", result.errorMessage());
        assertEquals("rate limited", result.output());
    }

    @Test
    void marksATruncatedCaptureWithoutAlteringItLater() {
        GatewayResponseCapture.Capture result = capture.capture(bytes("""
                {"choices":[{"message":{"content":"hello"},"finish_reason":"stop"}]}
                """), "application/json", true);

        assertTrue(result.truncated());
        assertTrue(result.output().endsWith("[truncated]"));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
