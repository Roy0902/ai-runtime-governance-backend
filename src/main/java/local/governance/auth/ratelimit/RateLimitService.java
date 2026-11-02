package local.governance.auth.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import local.governance.api.ApiException;
import local.governance.auth.persistence.AuthRepository;
import local.governance.config.GovernanceProperties;
import org.springframework.stereotype.Service;

@Service
public class RateLimitService {

    public static final String AUTH_SOURCE = "AUTH_SOURCE";
    public static final String SIGNUP_SOURCE = "SIGNUP_SOURCE";
    public static final String MAIL_SOURCE = "MAIL_SOURCE";
    public static final String MAIL_IDENTITY = "MAIL_IDENTITY";
    private static final int MAX_BUCKETS = 10_000;

    private final AuthRepository repository;
    private final Clock clock;
    private final GovernanceProperties properties;
    private final int mailIdentityPerMinute;
    private final int mailIdentityPerHour;

    public RateLimitService(AuthRepository repository, Clock clock, GovernanceProperties properties,
            @org.springframework.beans.factory.annotation.Value("${governance.mail-identity-per-minute:1}") int mailIdentityPerMinute,
            @org.springframework.beans.factory.annotation.Value("${governance.mail-identity-per-hour:5}") int mailIdentityPerHour) {
        this.repository = repository;
        this.clock = clock;
        this.properties = properties;
        this.mailIdentityPerMinute = mailIdentityPerMinute;
        this.mailIdentityPerHour = mailIdentityPerHour;
    }

    public String sourceKey(HttpServletRequest request) {
        String peer = normalize(request.getRemoteAddr());
        if (peer == null) {
            peer = request.getRemoteAddr();
        }
        if (!isTrusted(peer)) {
            return peer;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return peer;
        }
        String[] hops = forwarded.split(",");
        if (hops.length > 8) {
            throw new ApiException("REQUEST_INVALID", "Malformed forwarding metadata.");
        }
        String selected = null;
        for (int index = hops.length - 1; index >= 0; index -= 1) {
            String candidate = normalize(hops[index].trim());
            if (candidate == null) {
                throw new ApiException("REQUEST_INVALID", "Malformed forwarding metadata.");
            }
            selected = candidate;
            if (!isTrusted(candidate)) {
                return candidate;
            }
        }
        return selected == null ? peer : selected;
    }

    public void admit(String type, String key, Duration window, int limit, int retryAfterSeconds) {
        ensureBucket(type, key);
        Instant now = clock.instant();
        long count = repository.countRateEvents(type, key, now.minus(window), now);
        if (count >= limit) {
            throw ApiException.rateLimited(retryAfterSeconds);
        }
        repository.recordRateEvent(type, key, now);
    }

    public void checkMailIdentity(String email) {
        Instant now = clock.instant();
        long lastMinute = repository.countRateEvents(MAIL_IDENTITY, email, now.minusSeconds(60), now);
        if (lastMinute >= mailIdentityPerMinute) {
            throw ApiException.rateLimited(60);
        }
        long lastHour = repository.countRateEvents(MAIL_IDENTITY, email, now.minusSeconds(3600), now);
        if (lastHour >= mailIdentityPerHour) {
            throw ApiException.rateLimited(3600);
        }
        ensureBucket(MAIL_IDENTITY, email);
        repository.recordRateEvent(MAIL_IDENTITY, email, now);
    }

    public void checkCooldown(String email) {
        Instant now = clock.instant();
        Instant until = repository.cooldown(email).orElse(null);
        if (until != null && now.isBefore(until)) {
            long seconds = Math.max(1, Duration.between(now, until).toSeconds());
            throw ApiException.rateLimited((int) seconds);
        }
    }

    public record FailureOutcome(int count, int delaySeconds) {}

    public FailureOutcome recordFailure(String email) {
        Instant now = clock.instant();
        repository.recordFailure(email, now);
        long failures = repository.countFailures(email, now.minusSeconds(15 * 60), now);
        int delay = failures >= 20 ? 300 : failures >= 10 ? 30 : failures >= 5 ? 2 : 0;
        if (delay > 0) {
            repository.setCooldown(email, now.plusSeconds(delay), now);
        }
        return new FailureOutcome((int) failures, delay);
    }

    public void clearFailures(String email) {
        repository.clearFailures(email);
    }

    public long bucketCount(String type) {
        return repository.countBuckets(type);
    }

    private void ensureBucket(String type, String key) {
        if (repository.countBuckets(type) >= MAX_BUCKETS) {
            throw ApiException.unavailable();
        }
    }

    private boolean isTrusted(String peer) {
        List<String> trusted = properties.trustedProxyList();
        if (trusted.isEmpty()) {
            return false;
        }
        String normalized = normalize(peer);
        return trusted.stream().anyMatch((entry) -> entry.equals(peer) || entry.equals(normalized));
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.isEmpty()) {
            return null;
        }
        try {
            return InetAddress.getByName(candidate).getHostAddress();
        } catch (Exception exception) {
            return null;
        }
    }
}
