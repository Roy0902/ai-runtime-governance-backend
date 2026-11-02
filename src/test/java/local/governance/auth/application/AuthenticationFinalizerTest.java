package local.governance.auth.application;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import local.governance.api.ApiDtos.ContextDto;
import local.governance.api.ApiDtos.Factor;
import local.governance.api.ApiException;
import local.governance.auth.persistence.AuthRepository;
import local.governance.auth.persistence.AuthRepository.AccountRow;
import local.governance.auth.session.ContextMapper;
import local.governance.auth.session.SessionService;
import local.governance.auth.totp.FactorVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthenticationFinalizerTest {

    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private static final Factor FACTOR = new Factor("TOTP", "123456");

    private AuthRepository repository;
    private FactorVerifier factors;
    private SessionService sessions;
    private ContextMapper contexts;
    private AuthenticationFinalizer finalizer;

    @BeforeEach
    void setUp() {
        repository = org.mockito.Mockito.mock(AuthRepository.class);
        factors = org.mockito.Mockito.mock(FactorVerifier.class);
        sessions = org.mockito.Mockito.mock(SessionService.class);
        contexts = org.mockito.Mockito.mock(ContextMapper.class);
        finalizer = new AuthenticationFinalizer(repository, factors, sessions, contexts,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void rechecksCredentialVersionBeforeConsumingFactorOrCreatingSession() {
        AccountRow changed = account(8, "VERIFIED", "ENROLLED", null);
        when(repository.accountByIdForUpdate(changed.id())).thenReturn(Optional.of(changed));

        assertThrows(ApiException.class, () -> finalizer.complete(
                changed.id(), 7, FACTOR, new MockHttpServletRequest(), new MockHttpServletResponse()));

        verify(factors, never()).verify(changed, FACTOR);
        verify(sessions, never()).create(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void refusesToCreateAuthorityForAnAccountDisabledBeforeCommit() {
        AccountRow disabled = account(7, "DISABLED", "ENROLLED", null);
        when(repository.accountByIdForUpdate(disabled.id())).thenReturn(Optional.of(disabled));

        assertThrows(ApiException.class, () -> finalizer.complete(
                disabled.id(), 7, FACTOR, new MockHttpServletRequest(), new MockHttpServletResponse()));

        verify(factors, never()).verify(disabled, FACTOR);
        verify(sessions, never()).create(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void verifiesFactorOnlyAfterLockedAccountStateMatches() {
        AccountRow current = account(7, "VERIFIED", "ENROLLED", null);
        when(repository.accountByIdForUpdate(current.id())).thenReturn(Optional.of(current));
        when(sessions.read(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        when(sessions.create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("HUMAN"),
                org.mockito.ArgumentMatchers.eq(current.id()), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(NOW)))
                .thenReturn(org.mockito.Mockito.mock(SessionService.Current.class));
        when(contexts.context(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(current)))
                .thenReturn(new ContextDto("HUMAN", "csrf", null, null, null));

        ContextDto result = finalizer.complete(
                current.id(), 7, FACTOR, new MockHttpServletRequest(), new MockHttpServletResponse());

        assertNotNull(result);
        verify(factors).verify(current, FACTOR);
        verify(sessions).create(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("HUMAN"),
                org.mockito.ArgumentMatchers.eq(current.id()), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(NOW));
    }

    private static AccountRow account(long version, String status, String mfaState, Instant recoveryRequiredAt) {
        return new AccountRow(UUID.randomUUID(), "person@example.test", "versioned-verifier",
                NOW.minusSeconds(60), status, mfaState, UUID.randomUUID(), "ABSENT",
                NOW.minusSeconds(600), NOW.minusSeconds(60), null, recoveryRequiredAt, version);
    }
}
