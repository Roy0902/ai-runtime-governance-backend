package local.governance.gateway.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GatewaySetupResponse(
        UUID id,
        String name,
        String token,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSX", timezone = "UTC")
        Instant createdAt,
        String baseUrl,
        String chatCompletionsPath,
        List<String> models,
        String openCodeConfig,
        String vsCodeConfig) {}
