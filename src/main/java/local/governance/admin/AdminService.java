package local.governance.admin;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import local.governance.auth.persistence.AuthRepository;
import local.governance.auth.persistence.AuthRepository.AccountRow;
import local.governance.auth.persistence.AuthRepository.OperationRow;
import local.governance.config.DispatchState;
import local.governance.config.TestFaults;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {

    private final AuthRepository repository;
    private final DispatchState dispatchState;
    private final TestFaults faults;
    private final Clock clock;

    public AdminService(AuthRepository repository, DispatchState dispatchState, TestFaults faults, Clock clock) {
        this.repository = repository;
        this.dispatchState = dispatchState;
        this.faults = faults;
        this.clock = clock;
    }

    @Transactional
    public Map<String, Object> disable(AccountRow account, String reason, String operator, UUID correlationId) {
        faultCheck();
        Instant now = clock.instant();
        if ("DISABLED".equals(account.status())) {
            return audit(account, "ACCOUNT_DISABLE", reason, operator, "NO_CHANGE", "[]", correlationId, false);
        }
        repository.revokeAllSessions(account.id(), now);
        repository.cancelAllPendingOperations(account.id(), now);
        repository.setGateway(account.id(), "ABSENT", now);
        repository.setStatus(account.id(), "DISABLED", now, now);
        return audit(account, "ACCOUNT_DISABLE", reason, operator, "CHANGED",
                "[\"status\",\"gatewayEntitlement\"]", correlationId, true);
    }

    @Transactional
    public Map<String, Object> enable(AccountRow account, String reason, String operator, UUID correlationId) {
        faultCheck();
        Instant now = clock.instant();
        if (!"DISABLED".equals(account.status())) {
            return audit(account, "ACCOUNT_ENABLE", reason, operator, "NO_CHANGE", "[]", correlationId, false);
        }
        String status = account.emailVerifiedAt() == null ? "PENDING" : "VERIFIED";
        repository.setStatus(account.id(), status, null, now);
        return audit(account, "ACCOUNT_ENABLE", reason, operator, "CHANGED", "[\"status\"]", correlationId, true);
    }

    @Transactional
    public Map<String, Object> recoverMfa(AccountRow account, String reason, String operator, UUID correlationId) {
        faultCheck();
        if (account.emailVerifiedAt() == null) {
            throw new AdminException(4, "STATE_CONFLICT", "Account is not verified.");
        }
        Instant now = clock.instant();
        repository.revokeAllSessions(account.id(), now);
        repository.cancelAllPendingOperations(account.id(), now);
        repository.retireAllActiveSeeds(account.id(), now);
        repository.revokeAllActiveRecoverySets(account.id(), now);
        repository.setGateway(account.id(), "ABSENT", now);
        repository.setRecoveryRequired(account.id(), now);
        return audit(account, "ACCOUNT_RECOVER_MFA", reason, operator, "CHANGED",
                "[\"mfaState\",\"gatewayEntitlement\"]", correlationId, true);
    }

    @Transactional
    public Map<String, Object> grant(AccountRow account, String reason, String operator, UUID correlationId) {
        faultCheck();
        if ("DISABLED".equals(account.status()) || account.emailVerifiedAt() == null
                || !"ENROLLED".equals(account.mfaState())) {
            throw new AdminException(4, "STATE_CONFLICT", "Account is not eligible for gateway use.");
        }
        if ("GRANTED".equals(account.gatewayEntitlement())) {
            return audit(account, "GATEWAY_GRANT", reason, operator, "NO_CHANGE", "[]", correlationId, false);
        }
        repository.setGateway(account.id(), "GRANTED", clock.instant());
        return audit(account, "GATEWAY_GRANT", reason, operator, "CHANGED", "[\"gatewayEntitlement\"]",
                correlationId, true);
    }

    @Transactional
    public Map<String, Object> revoke(AccountRow account, String reason, String operator, UUID correlationId) {
        faultCheck();
        dispatchState.revoke(account.email());
        if ("REVOKED".equals(account.gatewayEntitlement())) {
            return audit(account, "GATEWAY_REVOKE", reason, operator, "NO_CHANGE", "[]", correlationId, false);
        }
        repository.setGateway(account.id(), "REVOKED", clock.instant());
        return audit(account, "GATEWAY_REVOKE", reason, operator, "CHANGED", "[\"gatewayEntitlement\"]",
                correlationId, true);
    }

    @Transactional
    public Map<String, Object> cancel(AccountRow account, UUID operationId, String reason, String operator,
            UUID correlationId) {
        faultCheck();
        OperationRow operation = repository.operationById(operationId)
                .filter((candidate) -> account.id().equals(candidate.accountId()))
                .orElseThrow(() -> new AdminException(3, "NOT_FOUND", "Operation not found."));
        if (!"PENDING".equals(operation.state())) {
            throw new AdminException(4, "STATE_CONFLICT", "Operation is not pending.");
        }
        repository.cancelOperation(operation.id(), clock.instant());
        return audit(account, "SECURITY_CANCEL", reason, operator, "CHANGED", "[\"operationState\"]",
                correlationId, true);
    }

    private Map<String, Object> audit(AccountRow account, String action, String reason, String operator,
            String outcome, String changedFields, UUID correlationId, boolean changed) {
        Instant now = clock.instant();
        UUID auditId = UUID.randomUUID();
        repository.insertAudit(auditId, now, operator, action, account.id(), reason, outcome, changedFields,
                correlationId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("action", action);
        body.put("accountId", account.id().toString());
        body.put("changed", changed);
        body.put("auditId", auditId.toString());
        return body;
    }

    private void faultCheck() {
        if (faults.enabled("db-commit")) {
            throw new IllegalStateException("database commit fault");
        }
    }
}
