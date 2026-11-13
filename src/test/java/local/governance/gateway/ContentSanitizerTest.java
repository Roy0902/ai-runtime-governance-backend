package local.governance.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ContentSanitizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void replacesDataUrisWithAMarker() {
        ContentSanitizer sanitizer = new ContentSanitizer(objectMapper);

        String cleaned = sanitizer.clean("""
                {"output":"see data:image/png;base64,iVBORw0KGgoAAAANSUhEUg=="}
                """);

        assertTrue(cleaned.contains("binary omitted: image/png"));
        assertFalse(cleaned.contains("iVBORw0KGgo"));
    }

    @Test
    void replacesLongBase64Arguments() {
        ContentSanitizer sanitizer = new ContentSanitizer(objectMapper);
        String payload = "A".repeat(2_048);

        String cleaned = sanitizer.clean("{\"arguments\":\"" + payload + "\"}");

        assertTrue(cleaned.contains("binary omitted: base64"));
        assertFalse(cleaned.contains(payload));
    }

    @Test
    void keepsOrdinaryText() {
        ContentSanitizer sanitizer = new ContentSanitizer(objectMapper);

        String cleaned = sanitizer.clean("""
                {"text":"Say hello","output":"done"}
                """);

        assertTrue(cleaned.contains("Say hello"));
        assertTrue(cleaned.contains("done"));
    }
}
