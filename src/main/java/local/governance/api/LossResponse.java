package local.governance.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

public record LossResponse(
        String causeCode,
        Integer missingCount,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
        Instant from,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
        Instant to) {}
