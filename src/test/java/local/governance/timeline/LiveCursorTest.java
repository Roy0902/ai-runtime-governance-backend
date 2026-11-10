package local.governance.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import local.governance.api.APIException;
import org.junit.jupiter.api.Test;

class LiveCursorTest {

    @Test
    void decodesTheLastSeenRow() {
        UUID id = UUID.randomUUID();
        Instant receivedAt = Instant.parse("2026-11-10T14:09:19.857517Z");

        LiveCursor cursor = LiveCursor.decode(receivedAt + "|" + id);

        assertEquals(receivedAt, cursor.receivedAt());
        assertEquals(id, cursor.id());
    }

    @Test
    void treatsStartAndBlankAsTheBeginning() {
        assertNull(LiveCursor.decode(LiveCursor.START));
        assertNull(LiveCursor.decode(""));
        assertNull(LiveCursor.decode(null));
    }

    @Test
    void rejectsMalformedCursors() {
        APIException exception = assertThrows(APIException.class, () -> LiveCursor.decode("not-a-cursor"));

        assertEquals("REQUEST_INVALID", exception.code());
    }
}
