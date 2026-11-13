package local.governance.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Best-effort reconstruction of a model response for usage accounting and
 * Langfuse. It never alters the bytes forwarded to the agent.
 */
@Component
public class GatewayResponseCapture {

    private static final int MAX_ERROR_MESSAGE = 500;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final java.util.Set<String> TEXT_PART_TYPES =
            java.util.Set.of("text", "input_text", "output_text");
    private static final java.util.Set<String> IMAGE_PART_TYPES =
            java.util.Set.of("image_url", "image", "input_image", "output_image");
    private static final java.util.Set<String> AUDIO_PART_TYPES =
            java.util.Set.of("audio", "input_audio", "output_audio");
    private static final java.util.Set<String> FILE_PART_TYPES =
            java.util.Set.of("file", "input_file", "output_file");

    public record ToolCall(String id, String name, String arguments) {}

    public record Capture(
            String output,
            String finishReason,
            List<String> finishReasons,
            Long inputTokens,
            Long outputTokens,
            Long cachedInputTokens,
            Long reasoningOutputTokens,
            Long totalTokens,
            BigDecimal providerReportedCostUsd,
            String responseModel,
            String responseId,
            String systemFingerprint,
            String serviceTier,
            List<String> toolCallNames,
            boolean toolCallObserved,
            boolean truncated,
            Map<String, Object> rawUsage,
            String errorType,
            String errorCode,
            String errorMessage,
            List<ToolCall> toolCalls,
            String reasoning) {}

    private final ObjectMapper objectMapper;

    public GatewayResponseCapture(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Capture capture(byte[] body, String contentType, boolean truncated) {
        String raw = body == null ? "" : new String(body, StandardCharsets.UTF_8);
        Accumulator accumulator = new Accumulator();
        if (contentType != null && contentType.toLowerCase().contains("text/event-stream")) {
            readSse(raw, accumulator);
        } else {
            readJson(raw, accumulator);
        }
        return accumulator.toCapture(truncated, raw);
    }

    private void readJson(String raw, Accumulator accumulator) {
        if (raw.isBlank()) {
            return;
        }
        try {
            JsonNode response = objectMapper.readTree(raw);
            common(response, accumulator);
            JsonNode choices = response.path("choices");
            if (choices.isArray()) {
                for (JsonNode choice : choices) {
                    finish(choice, accumulator);
                    JsonNode message = choice.path("message");
                    content(message.path("content"), accumulator);
                    reasoning(message, accumulator);
                    toolCalls(message.path("tool_calls"), accumulator);
                }
            }
        } catch (Exception exception) {
            accumulator.fallback = raw;
        }
    }

    private void readSse(String raw, Accumulator accumulator) {
        StringBuilder data = new StringBuilder();
        for (String line : raw.split("\n", -1)) {
            String value = line.stripTrailing();
            if (value.isEmpty()) {
                processEvent(data, accumulator);
                data.setLength(0);
            } else if (value.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(value.substring(5).stripLeading());
            }
        }
        processEvent(data, accumulator);
    }

    private void processEvent(StringBuilder data, Accumulator accumulator) {
        if (data.length() == 0) {
            return;
        }
        String payload = data.toString().trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return;
        }
        try {
            JsonNode event = objectMapper.readTree(payload);
            common(event, accumulator);
            JsonNode choices = event.path("choices");
            if (choices.isArray()) {
                for (JsonNode choice : choices) {
                    finish(choice, accumulator);
                    JsonNode delta = choice.path("delta");
                    content(delta.path("content"), accumulator);
                    reasoning(delta, accumulator);
                    toolCalls(delta.path("tool_calls"), accumulator);
                    if (delta.isEmpty()) {
                        JsonNode message = choice.path("message");
                        content(message.path("content"), accumulator);
                        reasoning(message, accumulator);
                        toolCalls(message.path("tool_calls"), accumulator);
                    }
                }
            }
        } catch (Exception exception) {
            // Ignore malformed chunks; the response is still forwarded unchanged.
        }
    }

    private void common(JsonNode event, Accumulator accumulator) {
        text(event.path("id"), value -> accumulator.responseId = value);
        text(event.path("model"), value -> accumulator.responseModel = value);
        text(event.path("system_fingerprint"), value -> accumulator.systemFingerprint = value);
        text(event.path("service_tier"), value -> accumulator.serviceTier = value);
        JsonNode usage = event.path("usage");
        if (usage.isObject() && !usage.isEmpty()) {
            readUsage(usage, accumulator);
        }
        JsonNode error = event.path("error");
        if (error.isObject()) {
            text(error.path("type"), value -> accumulator.errorType = value);
            text(error.path("code"), value -> accumulator.errorCode = value);
            text(error.path("message"), value -> accumulator.errorMessage = truncate(value));
        }
    }

    private void readUsage(JsonNode usage, Accumulator accumulator) {
        Long input = longValue(usage, "prompt_tokens", "input_tokens");
        Long output = longValue(usage, "completion_tokens", "output_tokens");
        Long total = longValue(usage, "total_tokens");
        Long cached = longValue(usage.path("prompt_tokens_details"), "cached_tokens");
        if (cached == null) {
            cached = longValue(usage.path("input_tokens_details"), "cached_tokens");
        }
        Long reasoning = longValue(usage.path("completion_tokens_details"), "reasoning_tokens");
        if (reasoning == null) {
            reasoning = longValue(usage.path("output_tokens_details"), "reasoning_tokens");
        }
        BigDecimal providerCost = decimalValue(usage, "cost", "total_cost");
        if (providerCost == null) {
            providerCost = decimalValue(usage.path("cost_details"), "total");
        }

        if (input != null) {
            accumulator.inputTokens = input;
        }
        if (output != null) {
            accumulator.outputTokens = output;
        }
        if (total != null) {
            accumulator.totalTokens = total;
        }
        if (cached != null) {
            accumulator.cachedInputTokens = cached;
        }
        if (reasoning != null) {
            accumulator.reasoningOutputTokens = reasoning;
        }
        if (providerCost != null) {
            accumulator.providerReportedCostUsd = providerCost;
        }
        try {
            accumulator.rawUsage = objectMapper.convertValue(usage, MAP_TYPE);
        } catch (Exception exception) {
            accumulator.rawUsage = null;
        }
    }

    private static void finish(JsonNode choice, Accumulator accumulator) {
        text(choice.path("finish_reason"), accumulator.finishReasons::add);
    }

    private static void content(JsonNode content, Accumulator accumulator) {
        if (content.isTextual()) {
            accumulator.text.append(content.asText());
            return;
        }
        if (content.isArray()) {
            for (JsonNode part : content) {
                appendPart(part, accumulator);
            }
            return;
        }
        if (content.isObject()) {
            appendPart(content, accumulator);
        }
    }

    private static void appendPart(JsonNode part, Accumulator accumulator) {
        if (part == null || !part.isObject()) {
            return;
        }
        String type = string(part.path("type"));
        if (TEXT_PART_TYPES.contains(type)) {
            String text = string(part.path("text"));
            if (text != null) {
                accumulator.text.append(text);
            }
            return;
        }
        appendMarker(accumulator, marker(type));
    }

    private static void appendMarker(Accumulator accumulator, String marker) {
        if (marker == null) {
            return;
        }
        if (!accumulator.text.isEmpty() && accumulator.text.charAt(accumulator.text.length() - 1) != '\n') {
            accumulator.text.append('\n');
        }
        accumulator.text.append(marker);
    }

    private static String marker(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        if (IMAGE_PART_TYPES.contains(type)) {
            return "[image]";
        }
        if (AUDIO_PART_TYPES.contains(type)) {
            return "[audio]";
        }
        if (FILE_PART_TYPES.contains(type)) {
            return "[file]";
        }
        return "[" + type + "]";
    }

    private static void reasoning(JsonNode node, Accumulator accumulator) {
        text(node.path("reasoning"), accumulator.reasoning::append);
        text(node.path("reasoning_content"), accumulator.reasoning::append);
    }

    private static void toolCalls(JsonNode toolCalls, Accumulator accumulator) {
        if (!toolCalls.isArray()) {
            return;
        }
        int position = 0;
        for (JsonNode toolCall : toolCalls) {
            String index = toolCall.path("index").isNumber()
                    ? toolCall.path("index").asText()
                    : null;
            String id = string(toolCall.path("id"));
            String key = index != null ? "i:" + index : id != null ? "id:" + id : "n:" + position;
            position += 1;
            ToolCallBuilder builder = accumulator.toolCalls.computeIfAbsent(key, (ignored) -> new ToolCallBuilder());
            if (id != null && !id.isBlank() && builder.id == null) {
                builder.id = id;
            }
            String name = string(toolCall.path("function").path("name"));
            if (name == null) {
                name = string(toolCall.path("name"));
            }
            if (name != null && !name.isBlank()) {
                builder.name = name;
                accumulator.toolCallNames.add(name);
            }
            String arguments = string(toolCall.path("function").path("arguments"));
            if (arguments == null) {
                arguments = string(toolCall.path("arguments"));
            }
            if (arguments != null) {
                builder.arguments.append(arguments);
            }
        }
    }

    private static void text(JsonNode node, java.util.function.Consumer<String> consumer) {
        if (node.isTextual() && !node.asText().isBlank()) {
            consumer.accept(node.asText());
        }
    }

    private static String string(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }

    private static Long longValue(JsonNode node, String... names) {
        if (!node.isObject()) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isIntegralNumber() || value.isNumber()) {
                return value.asLong();
            }
        }
        return null;
    }

    private static BigDecimal decimalValue(JsonNode node, String... names) {
        if (!node.isObject()) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isNumber()) {
                return value.decimalValue();
            }
        }
        return null;
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_ERROR_MESSAGE) {
            return value;
        }
        return value.substring(0, MAX_ERROR_MESSAGE);
    }

    private static final class Accumulator {
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final Set<String> finishReasons = new LinkedHashSet<>();
        private final Set<String> toolCallNames = new LinkedHashSet<>();
        private final Map<String, ToolCallBuilder> toolCalls = new LinkedHashMap<>();
        private Long inputTokens;
        private Long outputTokens;
        private Long cachedInputTokens;
        private Long reasoningOutputTokens;
        private Long totalTokens;
        private BigDecimal providerReportedCostUsd;
        private String responseModel;
        private String responseId;
        private String systemFingerprint;
        private String serviceTier;
        private Map<String, Object> rawUsage;
        private String errorType;
        private String errorCode;
        private String errorMessage;
        private String fallback;

        private Capture toCapture(boolean truncated, String raw) {
            String output = text.toString();
            if (output.isEmpty() && fallback != null) {
                output = fallback;
            }
            if (output.isEmpty() && errorMessage != null) {
                output = errorMessage;
            }
            if (!toolCallNames.isEmpty()) {
                if (!output.isEmpty()) {
                    output += "\n";
                }
                output += "[tool_calls] " + String.join(", ", toolCallNames);
            }
            if (truncated) {
                output += "\n[truncated]";
            }
            List<String> reasons = List.copyOf(finishReasons);
            return new Capture(
                    output,
                    reasons.isEmpty() ? null : reasons.getFirst(),
                    reasons,
                    inputTokens,
                    outputTokens,
                    cachedInputTokens,
                    reasoningOutputTokens,
                    totalTokens,
                    providerReportedCostUsd,
                    responseModel,
                    responseId,
                    systemFingerprint,
                    serviceTier,
                    List.copyOf(toolCallNames),
                    !toolCallNames.isEmpty(),
                    truncated,
                    rawUsage == null ? Map.of() : new LinkedHashMap<>(rawUsage),
                    errorType,
                    errorCode,
                    errorMessage,
                    toolCalls.values().stream()
                            .map((builder) -> new ToolCall(
                                    builder.id,
                                    builder.name,
                                    builder.arguments.isEmpty() ? null : builder.arguments.toString()))
                            .toList(),
                    reasoning.isEmpty() ? null : reasoning.toString());
        }
    }

    private static final class ToolCallBuilder {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}
