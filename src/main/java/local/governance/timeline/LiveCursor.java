package local.governance.timeline;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import local.governance.api.APIException;

/**
 * Read cursor for live timeline polling: the last row the client already has.
 *
 * <p>Unlike {@link TimelineCursor}, this cursor does not reference a stored
 * traversal. It carries the ordering key directly, so repeated polling never
 * creates traversal rows and cannot exhaust the traversal budget.
 */
public record LiveCursor(Instant receivedAt, UUID id) {

    public static final String START = "start";

    public static LiveCursor decode(String value) {
        if (value == null || value.isBlank() || START.equals(value)) {
            return null;
        }
        try {
            int split = value.lastIndexOf('|');
            if (split <= 0 || split == value.length() - 1) {
                throw invalid();
            }
            Instant receivedAt = OffsetDateTime.parse(value.substring(0, split)).toInstant();
            UUID id = UUID.fromString(value.substring(split + 1));
            return new LiveCursor(receivedAt, id);
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private static APIException invalid() {
        return new APIException("REQUEST_INVALID", "Check the request and try again.");
    }
}
