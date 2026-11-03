package local.governance.authentication.application;

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
import local.governance.authentication.api.request.CompleteEnrollmentRequest;
import local.governance.authentication.api.request.CompleteAuthenticatorReplacementRequest;
import local.governance.authentication.api.request.StartSecurityOperationRequest;
import local.governance.authentication.crypto.HashWorkLimiter;
import local.governance.authentication.crypto.PasswordHashService;
import local.governance.authentication.crypto.RecoveryCodeService;
import local.governance.authentication.crypto.SeedCipher;
import local.governance.authentication.crypto.TOTPService;
import local.governance.authentication.persistence.AuthenticationRepository;
import local.governance.authentication.persistence.AuthenticationRepository.AccountRow;
import local.governance.authentication.persistence.AuthenticationRepository.OperationRow;
import local.governance.authentication.persistence.AuthenticationRepository.RecoverySetRow;
import local.governance.authentication.persistence.AuthenticationRepository.SeedRow;
import local.governance.authentication.session.ContextMapper;
import local.governance.authentication.session.SessionService;
import local.governance.authentication.totp.FactorVerifier;
import local.governance.config.TestFaultRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class EnrollmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");

    private AuthenticationRepository repository;
    private TOTPService totp;
    private RecoveryCodeService recoveryCodes;
    private SeedCipher cipher;
    private FactorVerifier factors;
    private SessionService sessions;
    private EnrollmentService enrollment;
    private AccountRow account;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        repository = mock(AuthenticationRepository.class);
        totp = mock(TOTPService.class);
        recoveryCodes = mock(RecoveryCodeService.class);
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
                mock(PasswordHashService.class),
                mock(HashWorkLimiter.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(TestFaultRegistry.class));
    }

    @Test
    void rejectsMissingOperationIdAsInvalidRequest() {
        APIException exception = assertThrows(APIException.class, () -> enrollment.startReplacement(
                new StartSecurityOperationRequest(null), request));

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
                new CompleteEnrollmentRequest(operationId, null, "123456", true), request,
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
                new StartSecurityOperationRequest(operationId), request));

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
                new CompleteAuthenticatorReplacementRequest("12x456", true), operationId, request,
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
                new RecoveryCodeService.Generated(List.of("ABCD"), List.of(new byte[16])));
        when(repository.recoverySetById(any())).thenAnswer(invocation -> Optional.of(
                new RecoverySetRow(invocation.getArgument(0), account.id(), "PENDING", NOW,
                        NOW.plusSeconds(86_400), null, null)));
        when(recoveryCodes.format(any())).thenReturn("0123-4567-89AB-CDEF-0123-4567-89AB-CDEF");
        when(recoveryCodes.normalize(anyString())).thenReturn("0123456789ABCDEF0123456789ABCDEF");
        when(recoveryCodes.verifier(any(), any(), anyString())).thenReturn(new byte[] {1});
        when(repository.insertOperation(any(OperationRow.class))).thenReturn(insertSucceeds);
    }
}
