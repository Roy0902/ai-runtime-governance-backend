package local.governance.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import local.governance.api.APIException;
import org.springframework.stereotype.Component;

/**
 * Validates the supported OpenAI-compatible chat-completions subset and finds
 * the message that the request is currently submitting.
 *
 * <p>Only a request whose final message is a plain-text {@code user} message is
 * treated as a new human turn. Tool/assistant continuations are matched to an
 * earlier guarded turn by {@link GatewayConversationService}. Multimodal and
 * non-text content is rejected rather than silently passed through unguarded.
 */
@Component
public class GatewayRequestParser {

    public static final int MAX_MESSAGES = 1_000;

    private static final Set<String> ROLES =
            Set.of("system", "developer", "user", "assistant", "tool", "function");

    public enum Mode {
        NEW_TURN,
        CONTINUATION
    }

    public record Parsed(
            JsonNode body,
            String model,
            boolean stream,
            Mode mode,
            int userMessageIndex,
            String userText,
            String canonicalPrefix) {}

    private final ObjectMapper objectMapper;

    public GatewayRequestParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Parsed parse(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            throw APIException.invalid("body", "Provide a chat-completions request body.");
        }
        JsonNode body;
        try {
            body = objectMapper.readTree(rawBody);
        } catch (Exception exception) {
            throw APIException.invalid("body", "The request body is not valid JSON.");
        }
        if (body == null || !body.isObject()) {
            throw APIException.invalid("body", "The request body must be a JSON object.");
        }

        String model = text(body.get("model"));
        if (model == null || model.isBlank() || model.length() > 200) {
            throw APIException.invalid("model", "Provide a supported model identifier.");
        }

        JsonNode messages = body.get("messages");
        if (messages == null || !messages.isArray() || messages.isEmpty()) {
            throw APIException.invalid("messages", "Provide at least one chat message.");
        }
        if (messages.size() > MAX_MESSAGES) {
            throw APIException.invalid("messages", "The conversation is too long.");
        }

        List<JsonNode> canonicalMessages = new ArrayList<>(messages.size());
        for (JsonNode message : messages) {
            if (message == null || !message.isObject()) {
                throw APIException.invalid("messages", "Every chat message must be an object.");
            }
            String role = text(message.get("role"));
            if (role == null || !ROLES.contains(role)) {
                throw APIException.invalid("messages", "A chat message has an unsupported role.");
            }
            canonicalMessages.add(message);
        }

        JsonNode last = messages.get(messages.size() - 1);
        int userIndex = lastUserIndex(messages);
        if (userIndex < 0) {
            throw new APIException(
                    "GUARD_CONTEXT_UNKNOWN",
                    "The gateway could not identify the guarded human turn for this request.");
        }

        String role = text(last.get("role"));
        Mode mode = "user".equals(role) ? Mode.NEW_TURN : Mode.CONTINUATION;
        String userText = textContent(messages.get(userIndex).get("content"));
        if (userText == null) {
            throw new APIException(
                    "PROTOCOL_UNSUPPORTED",
                    "Only plain-text user messages are supported by this gateway.");
        }
        // Message text has no separate cap. The request body limit is the only
        // bound, so agent-generated turns such as compaction summaries are
        // guarded instead of rejected for being longer than a fixed slice.

        boolean stream = body.has("stream") && body.get("stream").isBoolean() && body.get("stream").asBoolean();
        String prefix = canonicalPrefix(canonicalMessages, userIndex);
        return new Parsed(body, model, stream, mode, userIndex, userText, prefix);
    }

    private static int lastUserIndex(JsonNode messages) {
        for (int index = messages.size() - 1; index >= 0; index -= 1) {
            if ("user".equals(text(messages.get(index).get("role")))) {
                return index;
            }
        }
        return -1;
    }

    private static String textContent(JsonNode content) {
        if (content == null || content.isNull()) {
            return null;
        }
        if (content.isTextual()) {
            return content.asText();
        }
        return null;
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static String canonicalPrefix(List<JsonNode> messages, int userIndex) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index <= userIndex; index += 1) {
            if (index > 0) {
                builder.append(',');
            }
            appendCanonical(builder, messages.get(index));
        }
        return builder.append(']').toString();
    }

    private static void appendCanonical(StringBuilder builder, JsonNode node) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            Iterator<String> fields = node.fieldNames();
            fields.forEachRemaining(names::add);
            names.sort(String::compareTo);
            builder.append('{');
            for (int index = 0; index < names.size(); index += 1) {
                if (index > 0) {
                    builder.append(',');
                }
                builder.append(quote(names.get(index))).append(':');
                appendCanonical(builder, node.get(names.get(index)));
            }
            builder.append('}');
        } else if (node.isArray()) {
            builder.append('[');
            for (int index = 0; index < node.size(); index += 1) {
                if (index > 0) {
                    builder.append(',');
                }
                appendCanonical(builder, node.get(index));
            }
            builder.append(']');
        } else if (node.isTextual()) {
            builder.append(quote(node.asText()));
        } else {
            builder.append(node.toString());
        }
    }

    private static String quote(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index += 1) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> builder.append(character);
            }
        }
        return builder.append('"').toString();
    }
}
