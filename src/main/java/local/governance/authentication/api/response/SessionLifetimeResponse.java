package local.governance.authentication.api.response;

import java.time.Instant;

public record SessionLifetimeResponse(
        Instant createdAt,
        Instant absoluteExpiresAt,
        Instant idleExpiresAt,
        Instant recentAuthExpiresAt) {}
