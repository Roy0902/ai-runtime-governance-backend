package local.governance.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.UUID;

public record TraceDetailResponse(
        UUID traceId,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
        Instant receivedAt,
        String executionStatus,
        String telemetryStatus,
        String guardOutcome,
        String identity,
        CoverageResponse coverage,
        LossResponse loss) {}
