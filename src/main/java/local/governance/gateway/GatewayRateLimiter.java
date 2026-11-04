package local.governance.gateway;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import local.governance.config.GovernanceProperties;
import org.springframework.stereotype.Component;

/**
 * Single-node admission control. The per-key fixed window is intentionally
 * simple; a shared store is required before running more than one gateway
 * instance.
 */
@Component
public class GatewayRateLimiter {

    private static final int MAX_WINDOWS = 10_000;

    private final Clock clock;
    private final int requestsPerMinute;
    private final Semaphore upstreamSlots;
    private final ConcurrentHashMap<UUID, Window> windows = new ConcurrentHashMap<>();

    public GatewayRateLimiter(GovernanceProperties properties, Clock clock) {
        this.clock = clock;
        this.requestsPerMinute = Math.max(1, properties.getGateway().getRequestsPerMinute());
        this.upstreamSlots = new Semaphore(
                Math.max(1, properties.getGateway().getMaxConcurrentUpstream()), true);
    }

    public boolean allow(UUID credentialId) {
        long minute = clock.instant().getEpochSecond() / 60;
        Window window = windows.compute(credentialId, (key, existing) ->
                existing == null || existing.minute() != minute
                        ? new Window(minute, 1)
                        : new Window(minute, existing.count() + 1));
        if (windows.size() > MAX_WINDOWS) {
            windows.entrySet().removeIf(entry -> entry.getValue().minute() < minute - 1);
        }
        return window.count() <= requestsPerMinute;
    }

    public boolean acquireUpstream() {
        try {
            return upstreamSlots.tryAcquire(1, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void releaseUpstream() {
        upstreamSlots.release();
    }

    private record Window(long minute, int count) {}
}
