package local.governance.api;

import java.time.Instant;

public record SessionDTO(
        Instant createdAt,
        Instant absoluteExpiresAt,
        Instant idleExpiresAt,
        Instant recentAuthExpiresAt) {}
