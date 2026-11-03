package local.governance.authentication.application;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import local.governance.authentication.api.request.AcknowledgementRequest;
import local.governance.authentication.api.response.AuthenticatorSetupResponse;
import local.governance.authentication.api.request.CompleteEnrollmentRequest;
import local.governance.authentication.api.request.CompleteAuthenticatorReplacementRequest;
import local.governance.authentication.api.response.AuthenticationContextResponse;
import local.governance.authentication.api.response.SecurityOperationResponse;
import local.governance.authentication.api.response.RecoveryCodeGenerationResponse;
import local.governance.authentication.api.request.RestartEnrollmentRequest;
import local.governance.authentication.api.request.StartSecurityOperationRequest;
import local.governance.api.APIException;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnrollmentService {

    private static final Duration OPERATION_LIFETIME = Duration.ofHours(24);
    private static final String ISSUER = "AI Runtime Governance";

    private final AuthenticationRepository repository;
    private final TOTPService totp;
    private final RecoveryCodeService recoveryCodes;
    private final SeedCipher cipher;
    private final FactorVerifier factors;
    private final SessionService sessions;
    private final ContextMapper contexts;
    private final PasswordHashService passwords;
    private final HashWorkLimiter hashLimiter;
    private final Clock clock;
    private final local.governance.config.TestFaultRegistry faults;

    public EnrollmentService(
            AuthenticationRepository repository,
            TOTPService totp,
            RecoveryCodeService recoveryCodes,
            SeedCipher cipher,
            FactorVerifier factors,
            SessionService sessions,
            ContextMapper contexts,
            PasswordHashService passwords,
            HashWorkLimiter hashLimiter,
            Clock clock,
            local.governance.config.TestFaultRegistry faults) {
        this.repository = repository;
        this.totp = totp;
        this.recoveryCodes = recoveryCodes;
        this.cipher = cipher;
        this.factors = factors;
        this.sessions = sessions;
        this.contexts = contexts;
        this.passwords = passwords;
        this.hashLimiter = hashLimiter;
        this.clock = clock;
        this.faults = faults;
    }

    @Transactional
    public AuthenticatorSetupResponse start(StartSecurityOperationRequest body, HttpServletRequest request) {
        SessionService.Current current = sessions.requireSetup(request);
        AccountRow account = repository.accountById(current.accountId()).orElseThrow(APIException::signInRequired);
        UUID operationId = requireOperationId(body.operationId());
        Instant now = clock.instant();
        repository.supersedePendingOperations(account.id(), current.purpose(), now);
        return createAuthenticatorOperation(account, current.purpose(), operationId);
    }

    @Transactional
    public AuthenticatorSetupResponse restart(RestartEnrollmentRequest body, HttpServletRequest request) {
        SessionService.Current current = sessions.requireSetup(request);
        AccountRow account = repository.accountById(current.accountId()).orElseThrow(APIException::signInRequired);
        verifyPassword(account, body.password());
        UUID operationId = requireOperationId(body.operationId());
        repository.supersedePendingOperations(account.id(), current.purpose(), clock.instant());
        return createAuthenticatorOperation(account, current.purpose(), operationId);
    }

    public SecurityOperationResponse readEnrollment(HttpServletRequest request) {
        SessionService.Current current = sessions.requireSetup(request);
        OperationRow operation = repository.pendingOperation(current.accountId(), current.purpose())
                .orElseThrow(APIException::notFound);
        return operation(operation);
    }

    @Transactional
    public AuthenticationContextResponse complete(CompleteEnrollmentRequest body, HttpServletRequest request, HttpServletResponse response) {
        SessionService.Current current = sessions.requireSetup(request);
        AccountRow account = repository.accountById(current.accountId()).orElseThrow(APIException::signInRequired);
        Instant now = clock.instant();
        UUID operationId = requireOperationId(body.operationId());
        OperationRow operation = repository.lockOperation(operationId)
                .orElseThrow(() -> new APIException("STALE_OPERATION", "This setup is no longer current. Start again."));
        if (!account.id().equals(operation.accountId()) || !"PENDING".equals(operation.state())
                || !current.purpose().equals(operation.purpose())) {
            throw new APIException("STALE_OPERATION", "This setup is no longer current. Start again.");
        }
        if (!now.isBefore(operation.expiresAt())) {
            throw new APIException("OPERATION_EXPIRED", "This setup expired. Start again.");
        }
        if (body.password() == null) {
            throw APIException.invalid("password", "Enter your password.");
        }
        verifyPassword(account, body.password());
        if (!body.recoveryCodesSaved()) {
            throw APIException.invalid("recoveryCodesSaved", "Confirm that you saved your recovery codes.");
        }
        SeedRow seed = repository.seedById(operation.candidateSeedId()).orElseThrow(APIException::notFound);
        verifyCandidate(account, seed, body.authenticatorCode());
        repository.activateSeed(seed.id(), now);
        repository.activateRecoverySet(operation.recoverySetId(), now);
        repository.completeOperation(operation.id(), now);
        repository.setMfaState(account.id(), "ENROLLED", now);
        if (account.scopeId() == null) {
            repository.setScope(account.id(), UUID.randomUUID(), now);
        }
        SessionService.Current rotated = sessions.rotate(response, current, "HUMAN", account.id(), null, now);
        AccountRow updated = repository.accountById(account.id()).orElse(account);
        return contexts.context(rotated, updated);
    }

    @Transactional
    public AuthenticatorSetupResponse startReplacement(StartSecurityOperationRequest body, HttpServletRequest request) {
        SessionService.Current current = sessions.requireHuman(request);
        sessions.requireRecentAuth(current);
        AccountRow account = repository.accountById(current.accountId()).orElseThrow(APIException::signInRequired);
        UUID operationId = requireOperationId(body.operationId());
        repository.supersedePendingOperations(account.id(), "AUTHENTICATOR_REPLACEMENT", clock.instant());
        return createAuthenticatorOperation(account, "AUTHENTICATOR_REPLACEMENT", operationId);
    }

    @Transactional
    public AuthenticationContextResponse completeReplacement(CompleteAuthenticatorReplacementRequest body, UUID operationId, HttpServletRequest request,
            HttpServletResponse response) {
        SessionService.Current current = sessions.requireHuman(request);
        sessions.requireRecentAuth(current);
        AccountRow account = repository.accountById(current.accountId()).orElseThrow(APIException::signInRequired);
        Instant now = clock.instant();
        OperationRow operation = repository.lockOperation(operationId)
                .orElseThrow(() -> new APIException("STALE_OPERATION", "This setup is no longer current. Start again."));
        if (!account.id().equals(operation.accountId()) || !"PENDING".equals(operation.state())
                || !"AUTHENTICATOR_REPLACEMENT".equals(operation.purpose())) {
            throw new APIException("STALE_OPERATION", "This setup is no longer current. Start again.");
        }
        if (!now.isBefore(operation.expiresAt())) {
            throw new APIException("OPERATION_EXPIRED", "This setup expired. Start again.");
        }
        if (!body.recoveryCodesSaved()) {
            throw APIException.invalid("recoveryCodesSaved", "Confirm that you saved your recovery codes.");
        }
        SeedRow seed = repository.seedById(operation.candidateSeedId()).orElseThrow(APIException::notFound);
        verifyCandidate(account, seed, body.authenticatorCode());
        if (faults.enabled("account-disable")) {
            throw new APIException("STALE_OPERATION", "This setup is no longer current. Start again.");
        }
        repository.activeSeed(account.id()).ifPresent((old) -> repository.retireSeed(old.id(), now));
        repository.activeRecoverySet(account.id()).ifPresent((old) -> repository.revokeRecoverySet(old.id(), now));
        repository.activateSeed(seed.id(), now);
        repository.activateRecoverySet(operation.recoverySetId(), now);
        repository.completeOperation(operation.id(), now);
        repository.revokeOtherSessions(account.id(), current.sessionId(), now);
        SessionService.Current rotated = sessions.rotateForSettings(response, current);
        AccountRow updated = repository.accountById(account.id()).orElse(account);
        return contexts.context(rotated, updated);
    }

    @Transactional
    public RecoveryCodeGenerationResponse startRegeneration(StartSecurityOperationRequest body, HttpServletRequest request) {
        SessionService.Current current = sessions.requireHuman(request);
        sessions.requireRecentAuth(current);
        AccountRow account = repository.accountById(current.accountId()).orElseThrow(APIException::signInRequired);
        UUID operationId = requireOperationId(body.operationId());
        Instant now = clock.instant();
        repository.supersedePendingOperations(account.id(), "RECOVERY_CODE_GENERATION", now);
        UUID setId = UUID.randomUUID();
        RecoveryCodeService.Generated generated = recoveryCodes.generate();
        repository.insertRecoverySet(new RecoverySetRow(setId, account.id(), "PENDING", now, now.plus(OPERATION_LIFETIME), null, null));
        storeCodes(setId, generated);
        OperationRow operation = new OperationRow(operationId, account.id(), "RECOVERY_CODE_GENERATION", "PENDING",
                null, setId, now, now.plus(OPERATION_LIFETIME), null, null, null, null, 0);
        insertOperation(operation);
        return new RecoveryCodeGenerationResponse(operation(operation), generated.display());
    }

    @Transactional
    public AuthenticationContextResponse completeRegeneration(AcknowledgementRequest body, UUID operationId, HttpServletRequest request,
            HttpServletResponse response) {
        SessionService.Current current = sessions.requireHuman(request);
        sessions.requireRecentAuth(current);
        AccountRow account = repository.accountById(current.accountId()).orElseThrow(APIException::signInRequired);
        Instant now = clock.instant();
        OperationRow operation = repository.lockOperation(operationId)
                .orElseThrow(() -> new APIException("STALE_OPERATION", "This setup is no longer current. Start again."));
        if (!account.id().equals(operation.accountId()) || !"PENDING".equals(operation.state())
                || !"RECOVERY_CODE_GENERATION".equals(operation.purpose())) {
            throw new APIException("STALE_OPERATION", "This setup is no longer current. Start again.");
        }
        if (!now.isBefore(operation.expiresAt())) {
            throw new APIException("OPERATION_EXPIRED", "This setup expired. Start again.");
        }
        if (!body.recoveryCodesSaved()) {
            throw APIException.invalid("recoveryCodesSaved", "Confirm that you saved your recovery codes.");
        }
        repository.activeRecoverySet(account.id()).ifPresent((old) -> repository.revokeRecoverySet(old.id(), now));
        repository.activateRecoverySet(operation.recoverySetId(), now);
        repository.completeOperation(operation.id(), now);
        if (faults.enabled("generation-rollback")) {
            throw APIException.unavailable();
        }
        repository.revokeOtherSessions(account.id(), current.sessionId(), now);
        SessionService.Current rotated = sessions.rotateForSettings(response, current);
        AccountRow updated = repository.accountById(account.id()).orElse(account);
        return contexts.context(rotated, updated);
    }

    public SecurityOperationResponse readOperation(UUID operationId, HttpServletRequest request) {
        SessionService.Current current = sessions.requireHuman(request);
        OperationRow operation = repository.operationById(operationId)
                .filter((candidate) -> current.accountId().equals(candidate.accountId()))
                .orElseThrow(APIException::notFound);
        return operation(operation);
    }

    @Transactional
    public void cancel(UUID operationId, HttpServletRequest request) {
        SessionService.Current current = sessions.requireHuman(request);
        OperationRow operation = repository.lockOperation(operationId)
                .filter((candidate) -> current.accountId().equals(candidate.accountId()))
                .orElseThrow(APIException::notFound);
        if (!"PENDING".equals(operation.state())) {
            throw new APIException("STALE_OPERATION", "This operation is no longer current.");
        }
        repository.cancelOperation(operation.id(), clock.instant());
    }

    private AuthenticatorSetupResponse createAuthenticatorOperation(AccountRow account, String purpose, UUID operationId) {
        Instant now = clock.instant();
        SeedRow seed = createSeed(account, now);
        UUID setId = UUID.randomUUID();
        RecoveryCodeService.Generated generated = recoveryCodes.generate();
        repository.insertRecoverySet(new RecoverySetRow(setId, account.id(), "PENDING", now, now.plus(OPERATION_LIFETIME), null, null));
        storeCodes(setId, generated);
        OperationRow operation = new OperationRow(operationId, account.id(), purpose, "PENDING", seed.id(), setId,
                now, now.plus(OPERATION_LIFETIME), null, null, null, null, 0);
        insertOperation(operation);
        String secret = cipher.decrypt(account.id(), seed.id(), seed.keyId(), seed.nonce(), seed.ciphertext());
        String uri = "otpauth://totp/" + ISSUER.replace(" ", "%20") + ":" + account.email()
                + "?secret=" + secret + "&issuer=" + ISSUER.replace(" ", "%20");
        return new AuthenticatorSetupResponse(operation(operation), secret, uri, generated.display());
    }

    private UUID requireOperationId(UUID operationId) {
        if (operationId == null) {
            throw APIException.invalid("operationId", "Provide a valid operation ID.");
        }
        return operationId;
    }

    private void insertOperation(OperationRow operation) {
        if (!repository.insertOperation(operation)) {
            throw new APIException("STALE_OPERATION", "This setup is no longer current. Start again.");
        }
    }

    private SeedRow createSeed(AccountRow account, Instant now) {
        UUID seedId = UUID.randomUUID();
        String secret = totp.newSecret();
        SeedCipher.Encrypted encrypted = cipher.encrypt(account.id(), seedId, secret);
        SeedRow seed = new SeedRow(seedId, account.id(), "PENDING", encrypted.keyId(), encrypted.nonce(),
                encrypted.ciphertext(), 1, now, null);
        repository.insertSeed(seed);
        return seed;
    }

    private void storeCodes(UUID setId, RecoveryCodeService.Generated generated) {
        // The set's account id is resolved from the persisted set; verifiers are
        // bound to the exact account and set identity.
        UUID accountId = repository.recoverySetById(setId).orElseThrow().accountId();
        for (String canonical : generated.raw().stream().map(recoveryCodes::format).map(recoveryCodes::normalize).toList()) {
            repository.insertRecoveryCode(UUID.randomUUID(), setId, recoveryCodes.verifier(accountId, setId, canonical));
        }
    }

    private void verifyPassword(AccountRow account, String password) {
        PasswordHashService.Verification verification = hashLimiter.submit(
                () -> passwords.verify(password == null ? "" : password, account.passwordHash()));
        if (verification == PasswordHashService.Verification.FAILED) {
            throw APIException.signInFailed();
        }
    }

    private void verifyCandidate(AccountRow account, SeedRow seed, String code) {
        if (!totp.hasValidCodeFormat(code)) {
            throw APIException.invalid("authenticatorCode", "Enter a six-digit authenticator code.");
        }
        String secret = factors.decryptSeed(account, seed);
        OptionalLong step = totp.verify(secret, code, clock.instant());
        if (step.isEmpty() || !repository.recordTotpStep(account.id(), step.getAsLong(), clock.instant())) {
            throw APIException.signInFailed();
        }
    }

    private SecurityOperationResponse operation(OperationRow row) {
        return new SecurityOperationResponse(row.id(), row.purpose(), row.state(), row.createdAt(), row.expiresAt());
    }
}
