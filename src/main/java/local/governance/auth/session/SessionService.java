package local.governance.auth.session;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import local.governance.api.ApiException;
import local.governance.auth.crypto.Tokens;
import local.governance.auth.persistence.AuthRepository;
import local.governance.auth.persistence.AuthRepository.SessionRow;
import local.governance.config.GovernanceProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class SessionService {

    public static final String COOKIE = "__Host-arg-session";
    public static final Duration HUMAN_ABSOLUTE = Duration.ofDays(30);
    public static final Duration HUMAN_IDLE = Duration.ofDays(7);
    public static final Duration SETUP_ABSOLUTE = Duration.ofHours(24);
    public static final Duration ANONYMOUS_ABSOLUTE = Duration.ofHours(24);
    public static final Duration RECENT_AUTH = Duration.ofMinutes(10);

    public record Current(UUID sessionId, UUID accountId, String context, String purpose, String csrfToken, SessionRow row) {}

    private final AuthRepository repository;
    private final Tokens tokens;
    private final Clock clock;
    private final GovernanceProperties properties;

    public SessionService(AuthRepository repository, Tokens tokens, Clock clock, GovernanceProperties properties) {
        this.repository = repository;
        this.tokens = tokens;
        this.clock = clock;
        this.properties = properties;
    }

    public Optional<Current> read(HttpServletRequest request) {
        String token = cookie(request);
        if (token == null) {
            return Optional.empty();
        }
        Optional<SessionRow> found = repository.sessionByToken(tokens.hash(token));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SessionRow row = found.get();
        Instant now = clock.instant();
        if (!"ACTIVE".equals(row.state()) || !now.isBefore(row.absoluteExpiresAt()) || !now.isBefore(row.idleExpiresAt())) {
            repository.endSession(row.id(), "ENDED", now);
            return Optional.empty();
        }
        return Optional.of(new Current(row.id(), row.accountId(), row.context(), row.purpose(), row.csrfToken(), row));
    }

    public Current require(HttpServletRequest request, String... contexts) {
        Current current = read(request).orElseThrow(ApiException::signInRequired);
        if (Arrays.stream(contexts).noneMatch((allowed) -> allowed.equals(current.context()))) {
            throw ApiException.signInRequired();
        }
        return current;
    }

    public Current requireHuman(HttpServletRequest request) {
        return require(request, "HUMAN");
    }

    public Current requireSetup(HttpServletRequest request) {
        return require(request, "SETUP");
    }

    public Current anonymous(HttpServletRequest request, HttpServletResponse response) {
        Optional<Current> existing = read(request);
        if (existing.isPresent()) {
            return existing.get();
        }
        return create(response, "ANONYMOUS", null, null, null);
    }

    public Current create(HttpServletResponse response, String context, UUID accountId, String purpose, Instant recentAuthAt) {
        Instant now = clock.instant();
        Duration absolute = switch (context) {
            case "HUMAN" -> HUMAN_ABSOLUTE;
            case "SETUP" -> SETUP_ABSOLUTE;
            default -> ANONYMOUS_ABSOLUTE;
        };
        Duration idle = context.equals("HUMAN") ? HUMAN_IDLE : absolute;
        return issue(response, context, accountId, purpose, now, now,
                now.plus(absolute), now.plus(idle), recentAuthAt,
                recentAuthAt == null ? null : recentAuthAt.plus(RECENT_AUTH));
    }

    private Current issue(HttpServletResponse response, String context, UUID accountId, String purpose,
            Instant createdAt, Instant activityAt, Instant absoluteExpiresAt, Instant idleExpiresAt,
            Instant recentAuthAt, Instant recentAuthExpiresAt) {
        String token = tokens.issue();
        String csrf = tokens.issue();
        SessionRow row = new SessionRow(
                UUID.randomUUID(),
                tokens.hash(token),
                context,
                accountId,
                purpose,
                csrf,
                createdAt,
                activityAt,
                absoluteExpiresAt,
                idleExpiresAt,
                recentAuthAt,
                recentAuthExpiresAt,
                "ACTIVE");
        repository.insertSession(row);
        writeCookie(response, token, Duration.between(clock.instant(), absoluteExpiresAt));
        return new Current(row.id(), accountId, context, purpose, row.csrfToken(), row);
    }

    public Current rotate(HttpServletResponse response, Current previous, String context, UUID accountId, String purpose, Instant recentAuthAt) {
        repository.endSession(previous.sessionId(), "ENDED", clock.instant());
        return create(response, context, accountId, purpose, recentAuthAt);
    }

    public Current rotateForSettings(HttpServletResponse response, Current previous) {
        Instant now = clock.instant();
        SessionRow row = previous.row();
        if (!"HUMAN".equals(previous.context()) || !"ACTIVE".equals(row.state())
                || !now.isBefore(row.absoluteExpiresAt()) || !now.isBefore(row.idleExpiresAt())) {
            throw ApiException.signInRequired();
        }
        requireRecentAuth(previous);
        Instant idleExpiresAt = now.plus(HUMAN_IDLE);
        if (idleExpiresAt.isAfter(row.absoluteExpiresAt())) {
            idleExpiresAt = row.absoluteExpiresAt();
        }
        repository.endSession(previous.sessionId(), "ENDED", now);
        return issue(response, "HUMAN", previous.accountId(), null, row.createdAt(), now,
                row.absoluteExpiresAt(), idleExpiresAt, row.recentAuthAt(), row.recentAuthExpiresAt());
    }

    public void end(HttpServletResponse response, Current current) {
        repository.endSession(current.sessionId(), "ENDED", clock.instant());
        clearCookie(response);
    }

    public void clearCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                ResponseCookie.from(COOKIE, "").httpOnly(true).secure(true).sameSite("Lax").path("/").maxAge(0).build().toString());
    }

    public void requireCsrf(HttpServletRequest request, Current current) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !originAllowed(origin)) {
            throw new ApiException("CSRF_REJECTED", "Your session changed. Reload the page and try again.");
        }
        String header = request.getHeader("X-CSRF-Token");
        if (header == null || header.isBlank()) {
            throw new ApiException("CSRF_REJECTED", "Your session changed. Reload the page and try again.");
        }
        boolean matches;
        try {
            matches = MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8),
                    current.csrfToken().getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            matches = false;
        }
        if (!matches) {
            throw new ApiException("CSRF_REJECTED", "Your session changed. Reload the page and try again.");
        }
    }

    /**
     * Browser requests must carry a valid CSRF token. A request with no Origin is
     * treated as a same-origin API client (the acceptance API project); browsers
     * send Origin on state-changing requests.
     */
    public void requireCsrfIfBrowser(HttpServletRequest request) {
        if (request.getHeader(HttpHeaders.ORIGIN) == null) {
            return;
        }
        Current current = read(request)
                .orElseThrow(() -> new ApiException("CSRF_REJECTED", "Your session changed. Reload the page and try again."));
        requireCsrf(request, current);
    }

    public boolean hasRecentAuth(Current current) {
        Instant expires = current.row().recentAuthExpiresAt();
        return expires != null && !clock.instant().isAfter(expires);
    }

    public void requireRecentAuth(Current current) {
        if (!hasRecentAuth(current)) {
            throw new ApiException("RECENT_AUTH_REQUIRED", "Confirm your identity to continue.");
        }
    }

    public void touch(Current current) {
        Instant now = clock.instant();
        Instant idle = now.plus(HUMAN_IDLE);
        if (idle.isAfter(current.row().absoluteExpiresAt())) {
            idle = current.row().absoluteExpiresAt();
        }
        repository.touchSession(current.sessionId(), now, idle);
    }

    public void setRecentAuth(Current current) {
        Instant now = clock.instant();
        repository.setRecentAuth(current.sessionId(), now, now.plus(RECENT_AUTH));
    }

    private String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        return Arrays.stream(request.getCookies())
                .filter((candidate) -> COOKIE.equals(candidate.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private void writeCookie(HttpServletResponse response, String token, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE, token)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private boolean originAllowed(String origin) {
        try {
            URI uri = URI.create(origin);
            URI base = URI.create(properties.getBaseUrl());
            if (uri.getHost() != null && base.getHost() != null && uri.getHost().equalsIgnoreCase(base.getHost())) {
                return true;
            }
            return uri.getHost() != null
                    && (uri.getHost().equals("127.0.0.1") || uri.getHost().equals("localhost"));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
