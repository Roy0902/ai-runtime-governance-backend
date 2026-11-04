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
        assertEquals(3, result.inputTokens());
        assertEquals(2, result.outputTokens());
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
        assertEquals(5, result.inputTokens());
        assertEquals(4, result.outputTokens());
        assertTrue(result.toolCallObserved());
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
