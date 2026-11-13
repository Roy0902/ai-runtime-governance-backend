package local.governance.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Backend-side cleaning for model and tool content.
 *
 * <p>Binary or base64 payloads are not part of the audit format. They are
 * replaced with a short marker before the content is persisted or exported, so
 * the frontend can render whatever the backend returns without having to
 * validate or filter formats itself.
 */
@Component
public class ContentSanitizer {

    private static final int MAX_TEXT = 16_384;
    private static final int BASE64_MIN = 1_024;
    private static final Pattern BASE64 = Pattern.compile("^[A-Za-z0-9+/=\\r\\n]+$");
    private static final Pattern DATA_URI = Pattern.compile(
            "data:([^;,]+)?(;[^,]*)?;base64,[A-Za-z0-9+/=\\r\\n]+", Pattern.DOTALL);

    private final ObjectMapper objectMapper;

    public ContentSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String clean(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            JsonNode cleaned = cleanNode(objectMapper.readTree(json));
            return objectMapper.writeValueAsString(cleaned);
        } catch (Exception exception) {
            return json;
        }
    }

    private JsonNode cleanNode(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(cleanText(node.asText()));
        }
        if (node.isObject()) {
            ObjectNode copy = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                copy.set(field.getKey(), cleanNode(field.getValue()));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = objectMapper.createArrayNode();
            for (JsonNode item : node) {
                copy.add(cleanNode(item));
            }
            return copy;
        }
        return node;
    }

    static String cleanText(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher dataUri = DATA_URI.matcher(text);
        if (dataUri.find()) {
            String mime = dataUri.group(1) == null ? "binary" : dataUri.group(1);
            return dataUri.replaceAll("[binary omitted: " + mime + ", base64]");
        }
        if (text.length() >= BASE64_MIN && BASE64.matcher(text).matches()) {
            return "[binary omitted: base64, " + text.length() + " chars]";
        }
        if (text.length() > MAX_TEXT) {
            return text.substring(0, MAX_TEXT) + "\n[truncated]";
        }
        return text;
    }
}
