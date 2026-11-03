package local.governance.timeline;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import local.governance.api.APIException;

/**
 * Opaque traversal cursor: the stored snapshot identity plus the next position.
 * The traversal row binds the scope and trace, so the cursor grants nothing on
 * its own and every page reauthorizes.
 */
public record TimelineCursor(UUID traversalId, int position) {

    public static String encode(UUID traversalId, int position) {
        String raw = traversalId + "|" + position;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static TimelineCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|");
            if (parts.length != 2) {
                throw invalid();
            }
            UUID traversalId = UUID.fromString(parts[0]);
            int position = Integer.parseInt(parts[1]);
            if (position < 0) {
                throw invalid();
            }
            return new TimelineCursor(traversalId, position);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static APIException invalid() {
        return new APIException("REQUEST_INVALID", "Check the request and try again.");
    }
}
