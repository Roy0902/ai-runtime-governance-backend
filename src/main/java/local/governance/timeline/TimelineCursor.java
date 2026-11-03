package local.governance.timeline;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import local.governance.api.APIException;

/**
 * Opaque, scope-bound keyset cursor. It records the last traversed
 * (receipt time, id) pair; it grants no access on its own because every page
 * reauthorizes and the encoded scope must match the session scope.
 */
public record TimelineCursor(UUID scopeId, Instant receivedAt, UUID id) {

    public static String encode(UUID scopeId, Instant receivedAt, UUID id) {
        String raw = scopeId + "|" + receivedAt.toEpochMilli() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static TimelineCursor decode(String cursor, UUID authenticatedScope) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|");
            if (parts.length != 3) {
                throw invalid();
            }
            UUID scope = UUID.fromString(parts[0]);
            if (!scope.equals(authenticatedScope)) {
                throw invalid();
            }
            return new TimelineCursor(
                    scope,
                    Instant.ofEpochMilli(Long.parseLong(parts[1])),
                    UUID.fromString(parts[2]));
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static APIException invalid() {
        return new APIException("REQUEST_INVALID", "Check the request and try again.");
    }
}
