package local.governance.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

public record TraceSummaryResponse(
        UUID traceId,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
        Instant receivedAt,
        String executionStatus,
        String telemetryStatus,
        String guardOutcome,
        String sessionId,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean tombstone) {}
