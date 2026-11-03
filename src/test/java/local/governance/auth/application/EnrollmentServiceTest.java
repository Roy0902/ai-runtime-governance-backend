package local.governance.auth.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import local.governance.api.APIException;
import local.governance.api.CompleteEnrollmentBody;
import local.governance.api.CompleteReplacementBody;
import local.governance.api.StartOperationBody;
import local.governance.auth.crypto.HashWorkLimiter;
import local.governance.auth.crypto.Passwords;
import local.governance.auth.crypto.RecoveryCodes;
import local.governance.auth.crypto.SeedCipher;
import local.governance.auth.crypto.TOTP;
import local.governance.auth.persistence.AuthRepository;
import local.governance.auth.persistence.AuthRepository.AccountRow;
import local.governance.auth.persistence.AuthRepository.OperationRow;
import local.governance.auth.persistence.AuthRepository.RecoverySetRow;
import local.governance.auth.persistence.AuthRepository.SeedRow;
import local.governance.auth.session.ContextMapper;
import local.governance.auth.session.SessionService;
import local.governance.auth.totp.FactorVerifier;
import local.governance.config.TestFaults;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class EnrollmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");

    private AuthRepository repository;
    private TOTP totp;
    private RecoveryCodes recoveryCodes;
    private SeedCipher cipher;
    private FactorVerifier factors;
    private SessionService sessions;
    private EnrollmentService enrollment;
    private AccountRow account;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        repository = mock(AuthRepository.class);
        totp = mock(TOTP.class);
        recoveryCodes = mock(RecoveryCodes.class);
        cipher = mock(SeedCipher.class);
        factors = mock(FactorVerifier.class);
        sessions = mock(SessionService.class);
        account = new AccountRow(UUID.randomUUID(), "person@example.test", "verifier", NOW,
                "VERIFIED", "ENROLLED", UUID.randomUUID(), "ABSENT", NOW, NOW, null, null, 0);
        request = new MockHttpServletRequest();

        when(sessions.requireHuman(request)).thenReturn(new SessionService.Current(
                UUID.randomUUID(), account.id(), "HUMAN", null, "csrf", null));
        when(sessions.requireSetup(request)).thenReturn(new SessionService.Current(
                UUID.randomUUID(), account.id(), "SETUP", "INITIAL_ENROLLMENT", "csrf", null));
        when(repository.accountById(account.id())).thenReturn(Optional.of(account));

        enrollment = new EnrollmentService(
                repository,
                totp,
                recoveryCodes,
                cipher,
                factors,
                sessions,
                mock(ContextMapper.class),
                mock(Passwords.class),
                mock(HashWorkLimiter.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(TestFaults.class));
    }

    @Test
    void rejectsMissingOperationIdAsInvalidRequest() {
        APIException exception = assertThrows(APIException.class, () -> enrollment.startReplacement(
                new StartOperationBody(null), request));

        assertEquals("REQUEST_INVALID", exception.code());
        assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        verify(repository, never()).supersedePendingOperations(any(), anyString(), any());
    }

    @Test
    void rejectsMissingEnrollmentPasswordAsInvalidRequest() {
        UUID operationId = UUID.randomUUID();
        UUID seedId = UUID.randomUUID();
        when(repository.lockOperation(operationId)).thenReturn(Optional.of(new OperationRow(
                operationId, account.id(), "INITIAL_ENROLLMENT", "PENDING", seedId, UUID.randomUUID(),
                NOW, NOW.plusSeconds(60), null, null, null, null, 0)));

        APIException exception = assertThrows(APIException.class, () -> enrollment.complete(
                new CompleteEnrollmentBody(operationId, null, "123456", true), request,
                new MockHttpServletResponse()));

        assertEquals("REQUEST_INVALID", exception.code());
        assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        verify(repository, never()).seedById(any());
    }

    @Test
    void reportsOperationInsertConflictAsConflict() {
        UUID operationId = UUID.randomUUID();
        prepareAuthenticatorCreation(false);

        APIException exception = assertThrows(APIException.class, () -> enrollment.startReplacement(
                new StartOperationBody(operationId), request));

        assertEquals("STALE_OPERATION", exception.code());
        assertEquals(HttpStatus.CONFLICT, exception.status());
        verify(repository).insertOperation(org.mockito.ArgumentMatchers.argThat(
                operation -> operation.id().equals(operationId)
                        && operation.purpose().equals("AUTHENTICATOR_REPLACEMENT")));
    }

    @Test
    void rejectsMalformedAuthenticatorCodeAsInvalidRequest() {
        UUID operationId = UUID.randomUUID();
        UUID seedId = UUID.randomUUID();
        OperationRow operation = new OperationRow(operationId, account.id(), "AUTHENTICATOR_REPLACEMENT",
                "PENDING", seedId, UUID.randomUUID(), NOW, NOW.plusSeconds(60), null, null, null, null, 0);
        SeedRow seed = new SeedRow(seedId, account.id(), "PENDING", "key", new byte[12], new byte[32],
                1, NOW, null);
        when(repository.lockOperation(operationId)).thenReturn(Optional.of(operation));
        when(repository.seedById(seedId)).thenReturn(Optional.of(seed));

        APIException exception = assertThrows(APIException.class, () -> enrollment.completeReplacement(
                new CompleteReplacementBody("12x456", true), operationId, request,
                new MockHttpServletResponse()));

        assertEquals("REQUEST_INVALID", exception.code());
        assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        verify(factors, never()).decryptSeed(account, seed);
    }

    private void prepareAuthenticatorCreation(boolean insertSucceeds) {
        when(totp.newSecret()).thenReturn("TESTSECRET");
        when(cipher.encrypt(any(), any(), anyString()))
                .thenReturn(new SeedCipher.Encrypted("key", new byte[12], new byte[32]));
        when(recoveryCodes.generate()).thenReturn(
                new RecoveryCodes.Generated(List.of("ABCD"), List.of(new byte[16])));
        when(repository.recoverySetById(any())).thenAnswer(invocation -> Optional.of(
                new RecoverySetRow(invocation.getArgument(0), account.id(), "PENDING", NOW,
                        NOW.plusSeconds(86_400), null, null)));
        when(recoveryCodes.format(any())).thenReturn("0123-4567-89AB-CDEF-0123-4567-89AB-CDEF");
        when(recoveryCodes.normalize(anyString())).thenReturn("0123456789ABCDEF0123456789ABCDEF");
        when(recoveryCodes.verifier(any(), any(), anyString())).thenReturn(new byte[] {1});
        when(repository.insertOperation(any(OperationRow.class))).thenReturn(insertSucceeds);
    }
}
