package local.governance.auth.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import local.governance.api.ApiException;
import local.governance.auth.crypto.Tokens;
import local.governance.auth.persistence.AuthRepository;
import local.governance.auth.persistence.AuthRepository.SessionRow;
import local.governance.config.GovernanceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class SessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");

    private AuthRepository repository;
    private Tokens tokens;
    private SessionService sessions;

    @BeforeEach
    void setUp() {
        repository = mock(AuthRepository.class);
        tokens = new Tokens();
        sessions = new SessionService(repository, tokens, Clock.fixed(NOW, ZoneOffset.UTC),
                new GovernanceProperties());
    }

    @Test
    void settingsRotationPreservesOriginalLifetimeAndRecentConfirmation() {
        SessionService.Current previous = human(NOW.minus(Duration.ofDays(2)), NOW.minusSeconds(300));
        MockHttpServletResponse response = new MockHttpServletResponse();

        SessionService.Current rotated = sessions.rotateForSettings(response, previous);

        assertEquals(previous.row().createdAt(), rotated.row().createdAt());
        assertEquals(previous.row().absoluteExpiresAt(), rotated.row().absoluteExpiresAt());
        assertEquals(previous.row().recentAuthAt(), rotated.row().recentAuthAt());
        assertEquals(previous.row().recentAuthExpiresAt(), rotated.row().recentAuthExpiresAt());
        assertEquals(NOW, rotated.row().lastActivityAt());
        assertEquals(NOW.plus(Duration.ofDays(7)), rotated.row().idleExpiresAt());
        assertNotEquals(previous.sessionId(), rotated.sessionId());
        long remaining = Duration.between(NOW, previous.row().absoluteExpiresAt()).toSeconds();
        assertTrue(response.getHeader("Set-Cookie").contains("Max-Age=" + remaining),
                "The rotated cookie must retain only the original remaining lifetime");
        verify(repository).endSession(previous.sessionId(), "ENDED", NOW);
        verify(repository).insertSession(any(SessionRow.class));
    }

    @Test
    void initialEnrollmentStartsANewHumanLifetime() {
        SessionService.Current human = human(NOW.minusSeconds(3600), null);
        SessionRow setup = new SessionRow(human.sessionId(), human.row().tokenHash(), "SETUP",
                human.accountId(), "INITIAL_ENROLLMENT", human.csrfToken(),
                NOW.minusSeconds(3600), NOW.minusSeconds(3600), NOW.plusSeconds(82800),
                NOW.plusSeconds(82800), null, null, "ACTIVE");
        SessionService.Current previous = new SessionService.Current(setup.id(), setup.accountId(),
                setup.context(), setup.purpose(), setup.csrfToken(), setup);

        SessionService.Current rotated = sessions.rotate(new MockHttpServletResponse(), previous,
                "HUMAN", previous.accountId(), null, NOW);

        assertEquals(NOW, rotated.row().createdAt());
        assertEquals(NOW.plus(Duration.ofDays(30)), rotated.row().absoluteExpiresAt());
        assertEquals(NOW, rotated.row().recentAuthAt());
    }

    @Test
    void exactTenMinuteConfirmationIsStillValid() {
        assertTrue(sessions.hasRecentAuth(human(NOW.minusSeconds(3600), NOW.minusSeconds(600))));
    }

    @Test
    void confirmationOlderThanTenMinutesIsRejected() {
        SessionService.Current current = human(NOW.minusSeconds(3600),
                NOW.minusSeconds(600).minusNanos(1));
        assertFalse(sessions.hasRecentAuth(current));
        assertThrows(ApiException.class, () -> sessions.requireRecentAuth(current));
    }

    @Test
    void expiredHumanAuthorityCannotBeRotatedIntoANewSession() {
        SessionService.Current previous = human(NOW.minus(Duration.ofDays(30)), NOW.minusSeconds(300));
        assertThrows(ApiException.class, () -> sessions.rotateForSettings(new MockHttpServletResponse(), previous));
    }

    @Test
    void settingsRotationCannotExtendAnAlmostExpiredAbsoluteDeadline() {
        SessionService.Current previous = human(NOW.minus(Duration.ofDays(30)).plusSeconds(60),
                NOW.minusSeconds(300));
        SessionService.Current rotated = sessions.rotateForSettings(new MockHttpServletResponse(), previous);
        assertEquals(NOW.plusSeconds(60), rotated.row().absoluteExpiresAt());
        assertEquals(rotated.row().absoluteExpiresAt(), rotated.row().idleExpiresAt());
    }

    @Test
    void settingsRotationDoesNotRefreshAnExpiredConfirmation() {
        SessionService.Current previous = human(NOW.minusSeconds(3600), NOW.minusSeconds(601));
        assertThrows(ApiException.class, () -> sessions.rotateForSettings(new MockHttpServletResponse(), previous));
    }

    private SessionService.Current human(Instant createdAt, Instant recentAuthAt) {
        UUID accountId = UUID.randomUUID();
        SessionRow row = new SessionRow(UUID.randomUUID(), tokens.hash(tokens.issue()), "HUMAN",
                accountId, null, tokens.issue(), createdAt, NOW.minusSeconds(60),
                createdAt.plus(Duration.ofDays(30)), NOW.plus(Duration.ofDays(7)).minusSeconds(60),
                recentAuthAt, recentAuthAt == null ? null : recentAuthAt.plusSeconds(600), "ACTIVE");
        return new SessionService.Current(row.id(), accountId, row.context(), row.purpose(),
                row.csrfToken(), row);
    }
}
