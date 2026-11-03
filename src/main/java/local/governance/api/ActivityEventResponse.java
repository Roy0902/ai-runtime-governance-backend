package local.governance.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.UUID;

public record ActivityEventResponse(
        UUID eventId,
        UUID traceId,
        UUID parentEventId,
        String relationship,
        String kind,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
        Instant receivedAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
        Instant sourceAt,
        Integer durationMs,
        String identity,
        EventMetadataResponse metadata) {}
