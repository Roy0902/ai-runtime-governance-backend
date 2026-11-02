package local.governance.auth.application;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import local.governance.api.ApiDtos.AcknowledgementBody;
import local.governance.api.ApiDtos.AuthenticatorSecretsDto;
import local.governance.api.ApiDtos.CompleteEnrollmentBody;
import local.governance.api.ApiDtos.CompleteReplacementBody;
import local.governance.api.ApiDtos.ContextDto;
import local.governance.api.ApiDtos.OperationDto;
import local.governance.api.ApiDtos.RecoverySecretsDto;
import local.governance.api.ApiDtos.RestartEnrollmentBody;
import local.governance.api.ApiDtos.StartOperationBody;
import local.governance.api.ApiException;
import local.governance.auth.crypto.HashWorkLimiter;
import local.governance.auth.crypto.Passwords;
import local.governance.auth.crypto.RecoveryCodes;
import local.governance.auth.crypto.SeedCipher;
import local.governance.auth.crypto.Totp;
import local.governance.auth.persistence.AuthRepository;
import local.governance.auth.persistence.AuthRepository.AccountRow;
import local.governance.auth.persistence.AuthRepository.OperationRow;
import local.governance.auth.persistence.AuthRepository.RecoverySetRow;
import local.governance.auth.persistence.AuthRepository.SeedRow;
import local.governance.auth.session.ContextMapper;
import local.governance.auth.session.SessionService;
import local.governance.auth.totp.FactorVerifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnrollmentService {

    private static final Duration OPERATION_LIFETIME = Duration.ofHours(24);
    private static final String ISSUER = "AI Runtime Governance";

    private final AuthRepository repository;
    private final Totp totp;
    private final RecoveryCodes recoveryCodes;
    private final SeedCipher cipher;
    private final FactorVerifier factors;
    private final SessionService sessions;
    private final ContextMapper contexts;
    private final Passwords passwords;
    private final HashWorkLimiter hashLimiter;
    private final Clock clock;
    private final local.governance.config.TestFaults faults;

    public EnrollmentService(
            AuthRepository repository,
            Totp totp,
            RecoveryCodes recoveryCodes,
            SeedCipher cipher,
            FactorVerifier factors,
            SessionService sessions,
            ContextMapper contexts,
            Passwords passwords,
            HashWorkLimiter hashLimiter,
            Clock clock,
            local.governance.config.TestFaults faults) {
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
    public AuthenticatorSecretsDto start(StartOperationBody body, HttpServletRequest request, HttpServletResponse response) {
        SessionService.Current current = sessions.requireSetup(request);
        AccountRow account = repository.accountById(current.accountId()).orElseThrow(ApiException::signInRequired);
        UUID operationId = body.operationId() == null ? UUID.randomUUID() : body.operationId();
        if (repository.operationById(operationId).isPresent()) {
            throw new ApiException("STALE_OPERATION", "This setup is no longer current. Start again.");
        }
        return createEnrollment(account, current.purpose(), operationId);
    }

    @Transactional
    public AuthenticatorSecretsDto restart(RestartEnrollmentBody body, HttpServletRequest request,
            HttpServletResponse response) {
        SessionService.Current current = sessions.requireSetup(request);
        AccountRow account = repository.accountById(current.accountId()).orElseThrow(ApiException::signInRequired);
        verifyPassword(account, body.password());
        UUID operationId = body.operationId() == null ? UUID.randomUUID() : body.operationId();
        repository.supersedePendingOperations(account.id(), current.purpose(), clock.instant());
        return createEnrollment(account, current.purpose(), operationId);
    }

    public OperationDto readEnrollment(HttpServletRequest request) {
        SessionService.Current current = sessions.requireSetup(request);
        OperationRow operation = repository.pendingOperation(current.accountId(), current.purpose())
                .orElseThrow(ApiException::notFound);
        return operation(operation);
    }

    @Transactional
    public ContextDto complete(CompleteEnrollmentBody body, HttpServletRequest request, HttpServletResponse response) {
        SessionService.Current current = sessions.requireSetup(request);
        AccountRow account = repository.accountById(current.accountId()).orElseThrow(ApiException::signInRequired);
        Instant now = clock.instant();
        OperationRow operation = repository.lockOperation(body.operationId())
                .orElseThrow(() -> new ApiException("STALE_OPERATION", "This setup is no longer current. Start again."));
        if (!account.id().equals(operation.accountId()) || !"PENDING".equals(operation.state())
                || !current.purpose().equals(operation.purpose())) {
            throw new ApiException("STALE_OPERATION", "This setup is no longer current. Start again.");
        }
        if (!now.isBefore(operation.expiresAt())) {
            throw new ApiException("OPERATION_EXPIRED", "This setup expired. Start again.");
        }
        if (body.password() != null && !body.password().isBlank()) {
            verifyPassword(account, body.password());
        }
        if (!body.recoveryCodesSaved()) {
            throw ApiException.invalid("recoveryCodesSaved", "Confirm that you saved your recovery codes.");
        }
        SeedRow seed = repository.seedById(operation.candidateSeedId()).orElseThrow(ApiException::notFound);
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
    public AuthenticatorSecretsDto startReplacement(StartOperationBody body, HttpServletRequest request,
            HttpServletResponse response) {
        SessionService.Current current = sessions.requireHuman(request);
        sessions.requireRecentAuth(current);
        AccountRow account = repository.accountById(current.accountId()).orElseThrow(ApiException::signInRequired);
        UUID operationId = body.operationId() == null ? UUID.randomUUID() : body.operationId();
        repository.supersedePendingOperations(account.id(), "AUTHENTICATOR_REPLACEMENT", clock.instant());
        return createReplacement(account, operationId);
    }

    @Transactional
    public ContextDto completeReplacement(CompleteReplacementBody body, UUID operationId, HttpServletRequest request,
            HttpServletResponse response) {
        SessionService.Current current = sessions.requireHuman(request);
        sessions.requireRecentAuth(current);
        AccountRow account = repository.accountById(current.accountId()).orElseThrow(ApiException::signInRequired);
        Instant now = clock.instant();
        OperationRow operation = repository.lockOperation(operationId)
                .orElseThrow(() -> new ApiException("STALE_OPERATION", "This setup is no longer current. Start again."));
        if (!account.id().equals(operation.accountId()) || !"PENDING".equals(operation.state())
                || !"AUTHENTICATOR_REPLACEMENT".equals(operation.purpose())) {
            throw new ApiException("STALE_OPERATION", "This setup is no longer current. Start again.");
        }
        if (!now.isBefore(operation.expiresAt())) {
            throw new ApiException("OPERATION_EXPIRED", "This setup expired. Start again.");
        }
        if (!body.recoveryCodesSaved()) {
            throw ApiException.invalid("recoveryCodesSaved", "Confirm that you saved your recovery codes.");
        }
        SeedRow seed = repository.seedById(operation.candidateSeedId()).orElseThrow(ApiException::notFound);
        verifyCandidate(account, seed, body.authenticatorCode());
        if (faults.enabled("account-disable")) {
            throw new ApiException("STALE_OPERATION", "This setup is no longer current. Start again.");
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
    public RecoverySecretsDto startRegeneration(StartOperationBody body, HttpServletRequest request,
            HttpServletResponse response) {
        SessionService.Current current = sessions.requireHuman(request);
        sessions.requireRecentAuth(current);
        AccountRow account = repository.accountById(current.accountId()).orElseThrow(ApiException::signInRequired);
        UUID operationId = body.operationId() == null ? UUID.randomUUID() : body.operationId();
        Instant now = clock.instant();
        repository.supersedePendingOperations(account.id(), "RECOVERY_CODE_GENERATION", now);
        UUID setId = UUID.randomUUID();
        RecoveryCodes.Generated generated = recoveryCodes.generate();
        repository.insertRecoverySet(new RecoverySetRow(setId, account.id(), "PENDING", now, now.plus(OPERATION_LIFETIME), null, null));
        storeCodes(setId, generated);
        OperationRow operation = new OperationRow(operationId, account.id(), "RECOVERY_CODE_GENERATION", "PENDING",
                null, setId, now, now.plus(OPERATION_LIFETIME), null, null, null, null, 0);
        repository.insertOperation(operation);
        return new RecoverySecretsDto(operation(operation), generated.display());
    }

    @Transactional
    public ContextDto completeRegeneration(AcknowledgementBody body, UUID operationId, HttpServletRequest request,
            HttpServletResponse response) {
        SessionService.Current current = sessions.requireHuman(request);
        sessions.requireRecentAuth(current);
        AccountRow account = repository.accountById(current.accountId()).orElseThrow(ApiException::signInRequired);
        Instant now = clock.instant();
        OperationRow operation = repository.lockOperation(operationId)
                .orElseThrow(() -> new ApiException("STALE_OPERATION", "This setup is no longer current. Start again."));
        if (!account.id().equals(operation.accountId()) || !"PENDING".equals(operation.state())
                || !"RECOVERY_CODE_GENERATION".equals(operation.purpose())) {
            throw new ApiException("STALE_OPERATION", "This setup is no longer current. Start again.");
        }
        if (!now.isBefore(operation.expiresAt())) {
            throw new ApiException("OPERATION_EXPIRED", "This setup expired. Start again.");
        }
        if (!body.recoveryCodesSaved()) {
            throw ApiException.invalid("recoveryCodesSaved", "Confirm that you saved your recovery codes.");
        }
        repository.activeRecoverySet(account.id()).ifPresent((old) -> repository.revokeRecoverySet(old.id(), now));
        repository.activateRecoverySet(operation.recoverySetId(), now);
        repository.completeOperation(operation.id(), now);
        if (faults.enabled("generation-rollback")) {
            throw ApiException.unavailable();
        }
        repository.revokeOtherSessions(account.id(), current.sessionId(), now);
        SessionService.Current rotated = sessions.rotateForSettings(response, current);
        AccountRow updated = repository.accountById(account.id()).orElse(account);
        return contexts.context(rotated, updated);
    }

    public OperationDto readOperation(UUID operationId, HttpServletRequest request) {
        SessionService.Current current = sessions.requireHuman(request);
        OperationRow operation = repository.operationById(operationId)
                .filter((candidate) -> current.accountId().equals(candidate.accountId()))
                .orElseThrow(ApiException::notFound);
        return operation(operation);
    }

    @Transactional
    public void cancel(UUID operationId, HttpServletRequest request) {
        SessionService.Current current = sessions.requireHuman(request);
        OperationRow operation = repository.lockOperation(operationId)
                .filter((candidate) -> current.accountId().equals(candidate.accountId()))
                .orElseThrow(ApiException::notFound);
        if (!"PENDING".equals(operation.state())) {
            throw new ApiException("STALE_OPERATION", "This operation is no longer current.");
        }
        repository.cancelOperation(operation.id(), clock.instant());
    }

    private AuthenticatorSecretsDto createEnrollment(AccountRow account, String purpose, UUID operationId) {
        Instant now = clock.instant();
        repository.supersedePendingOperations(account.id(), purpose, now);
        SeedRow seed = createSeed(account, now);
        UUID setId = UUID.randomUUID();
        RecoveryCodes.Generated generated = recoveryCodes.generate();
        repository.insertRecoverySet(new RecoverySetRow(setId, account.id(), "PENDING", now, now.plus(OPERATION_LIFETIME), null, null));
        storeCodes(setId, generated);
        OperationRow operation = new OperationRow(operationId, account.id(), purpose, "PENDING", seed.id(), setId,
                now, now.plus(OPERATION_LIFETIME), null, null, null, null, 0);
        repository.insertOperation(operation);
        String secret = cipher.decrypt(account.id(), seed.id(), seed.keyId(), seed.nonce(), seed.ciphertext());
        String uri = "otpauth://totp/" + ISSUER.replace(" ", "%20") + ":" + account.email()
                + "?secret=" + secret + "&issuer=" + ISSUER.replace(" ", "%20");
        return new AuthenticatorSecretsDto(operation(operation), secret, uri, generated.display());
    }

    private AuthenticatorSecretsDto createReplacement(AccountRow account, UUID operationId) {
        Instant now = clock.instant();
        SeedRow seed = createSeed(account, now);
        UUID setId = UUID.randomUUID();
        RecoveryCodes.Generated generated = recoveryCodes.generate();
        repository.insertRecoverySet(new RecoverySetRow(setId, account.id(), "PENDING", now, now.plus(OPERATION_LIFETIME), null, null));
        storeCodes(setId, generated);
        OperationRow operation = new OperationRow(operationId, account.id(), "AUTHENTICATOR_REPLACEMENT", "PENDING",
                seed.id(), setId, now, now.plus(OPERATION_LIFETIME), null, null, null, null, 0);
        repository.insertOperation(operation);
        String secret = cipher.decrypt(account.id(), seed.id(), seed.keyId(), seed.nonce(), seed.ciphertext());
        String uri = "otpauth://totp/" + ISSUER.replace(" ", "%20") + ":" + account.email()
                + "?secret=" + secret + "&issuer=" + ISSUER.replace(" ", "%20");
        return new AuthenticatorSecretsDto(operation(operation), secret, uri, generated.display());
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

    private void storeCodes(UUID setId, RecoveryCodes.Generated generated) {
        // The set's account id is resolved from the persisted set; verifiers are
        // bound to the exact account and set identity.
        UUID accountId = repository.recoverySetById(setId).orElseThrow().accountId();
        for (String canonical : generated.raw().stream().map(recoveryCodes::format).map(recoveryCodes::normalize).toList()) {
            repository.insertRecoveryCode(UUID.randomUUID(), setId, recoveryCodes.verifier(accountId, setId, canonical));
        }
    }

    private void verifyPassword(AccountRow account, String password) {
        Passwords.Verification verification = hashLimiter.submit(
                () -> passwords.verify(password == null ? "" : password, account.passwordHash()));
        if (verification == Passwords.Verification.FAILED) {
            throw ApiException.signInFailed();
        }
    }

    private void verifyCandidate(AccountRow account, SeedRow seed, String code) {
        String secret = factors.decryptSeed(account, seed);
        OptionalLong step = totp.verify(secret, code, clock.instant());
        if (step.isEmpty() || !repository.recordTotpStep(account.id(), step.getAsLong(), clock.instant())) {
            throw ApiException.signInFailed();
        }
    }

    private OperationDto operation(OperationRow row) {
        return new OperationDto(row.id(), row.purpose(), row.state(), row.createdAt(), row.expiresAt());
    }
}
