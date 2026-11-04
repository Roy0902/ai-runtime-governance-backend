package local.governance.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Best-effort reconstruction of a model response for Langfuse and timeline
 * metadata. It never alters the bytes forwarded to the agent.
 */
@Component
public class GatewayResponseCapture {

    public record Capture(
            String output,
            String finishReason,
            Integer inputTokens,
            Integer outputTokens,
            boolean toolCallObserved,
            boolean truncated) {}

    private final ObjectMapper objectMapper;

    public GatewayResponseCapture(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Capture capture(byte[] body, String contentType, boolean truncated) {
        String raw = body == null ? "" : new String(body, StandardCharsets.UTF_8);
        Capture capture = contentType != null && contentType.toLowerCase().contains("text/event-stream")
                ? captureSse(raw)
                : captureJson(raw);
        if (truncated) {
            String output = (capture.output() == null ? "" : capture.output()) + "\n[truncated]";
            return new Capture(
                    output,
                    capture.finishReason(),
                    capture.inputTokens(),
                    capture.outputTokens(),
                    capture.toolCallObserved(),
                    true);
        }
        return capture;
    }

    private Capture captureSse(String raw) {
        StringBuilder text = new StringBuilder();
        List<JsonNode> toolCalls = new ArrayList<>();
        String finishReason = null;
        Integer inputTokens = null;
        Integer outputTokens = null;
        StringBuilder data = new StringBuilder();

        for (String line : raw.split("\n", -1)) {
            String value = line.stripTrailing();
            if (value.isEmpty()) {
                if (data.length() > 0) {
                    Chunk chunk = chunk(data.toString(), text, toolCalls);
                    finishReason = chunk.finishReason() == null ? finishReason : chunk.finishReason();
                    inputTokens = chunk.inputTokens() == null ? inputTokens : chunk.inputTokens();
                    outputTokens = chunk.outputTokens() == null ? outputTokens : chunk.outputTokens();
                    data.setLength(0);
                }
            } else if (value.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(value.substring(5).stripLeading());
            }
        }
        if (data.length() > 0) {
            Chunk chunk = chunk(data.toString(), text, toolCalls);
            finishReason = chunk.finishReason() == null ? finishReason : chunk.finishReason();
            inputTokens = chunk.inputTokens() == null ? inputTokens : chunk.inputTokens();
            outputTokens = chunk.outputTokens() == null ? outputTokens : chunk.outputTokens();
        }

        return new Capture(
                output(text, toolCalls),
                finishReason,
                inputTokens,
                outputTokens,
                !toolCalls.isEmpty(),
                false);
    }

    private Chunk chunk(String data, StringBuilder text, List<JsonNode> toolCalls) {
        if (data.isBlank() || "[DONE]".equals(data.trim())) {
            return new Chunk(null, null, null);
        }
        try {
            JsonNode event = objectMapper.readTree(data);
            JsonNode choice = event.path("choices").path(0);
            String content = choice.path("delta").path("content").isTextual()
                    ? choice.path("delta").path("content").asText()
                    : null;
            if (content != null) {
                text.append(content);
            }
            JsonNode deltaToolCalls = choice.path("delta").path("tool_calls");
            if (deltaToolCalls.isArray()) {
                deltaToolCalls.forEach(toolCalls::add);
            }
            String finishReason = choice.path("finish_reason").isTextual()
                    ? choice.path("finish_reason").asText()
                    : null;
            JsonNode usage = event.path("usage");
            return new Chunk(
                    finishReason,
                    integer(usage, "prompt_tokens", "input_tokens"),
                    integer(usage, "completion_tokens", "output_tokens"));
        } catch (Exception exception) {
            return new Chunk(null, null, null);
        }
    }

    private Capture captureJson(String raw) {
        if (raw.isBlank()) {
            return new Capture("", null, null, null, false, false);
        }
        try {
            JsonNode response = objectMapper.readTree(raw);
            JsonNode choice = response.path("choices").path(0);
            String content = choice.path("message").path("content").isTextual()
                    ? choice.path("message").path("content").asText()
                    : null;
            JsonNode toolCalls = choice.path("message").path("tool_calls");
            JsonNode usage = response.path("usage");
            return new Capture(
                    content == null ? raw : content,
                    choice.path("finish_reason").isTextual()
                            ? choice.path("finish_reason").asText()
                            : null,
                    integer(usage, "prompt_tokens", "input_tokens"),
                    integer(usage, "completion_tokens", "output_tokens"),
                    toolCalls.isArray() && !toolCalls.isEmpty(),
                    false);
        } catch (Exception exception) {
            return new Capture(raw, null, null, null, false, false);
        }
    }

    private static String output(StringBuilder text, List<JsonNode> toolCalls) {
        if (toolCalls.isEmpty()) {
            return text.toString();
        }
        StringBuilder output = new StringBuilder(text);
        if (output.length() > 0) {
            output.append('\n');
        }
        output.append("[tool_calls] ").append(toolCalls);
        return output.toString();
    }

    private static Integer integer(JsonNode usage, String... names) {
        for (String name : names) {
            JsonNode value = usage.path(name);
            if (value.isIntegralNumber()) {
                return value.asInt();
            }
        }
        return null;
    }

    private record Chunk(String finishReason, Integer inputTokens, Integer outputTokens) {}
}
