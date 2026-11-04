package local.governance.integration;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import local.governance.api.APIException;
import local.governance.authentication.crypto.SecureTokenService;
import local.governance.authentication.persistence.AuthenticationRepository;
import local.governance.authentication.persistence.AuthenticationRepository.AccountRow;
import local.governance.integration.HookCredentialRepository.Row;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HookCredentialService {

    public record Summary(
            UUID id,
            String integration,
            String tokenPrefix,
            java.time.Instant createdAt,
            java.time.Instant revokedAt) {}

    public record Issued(
            UUID id,
            String integration,
            String token,
            String eventsPath,
            java.time.Instant createdAt) {}

    public record Principal(UUID accountId, UUID scopeId) {}

    private final HookCredentialRepository credentials;
    private final AuthenticationRepository accounts;
    private final SecureTokenService tokens;
    private final Clock clock;

    public HookCredentialService(
            HookCredentialRepository credentials,
            AuthenticationRepository accounts,
            SecureTokenService tokens,
            Clock clock) {
        this.credentials = credentials;
        this.accounts = accounts;
        this.tokens = tokens;
        this.clock = clock;
    }

    public List<Summary> list(UUID accountId) {
        requireEligibleAccount(accountId);
        return credentials.forAccount(accountId).stream()
                .map(row -> new Summary(
                        row.id(), row.integration(), row.tokenPrefix(), row.createdAt(), row.revokedAt()))
                .toList();
    }

    @Transactional
    public Issued issue(UUID accountId, HookIntegration integration) {
        AccountRow account = requireEligibleAccount(accountId);
        if (credentials.activeForAccount(accountId, integration.id()).isPresent()) {
            throw new APIException("HOOK_CREDENTIAL_EXISTS", "Revoke the existing integration token before creating another.");
        }
        String prefix = "arg_hook_" + integration.id();
        String token = prefix + "_" + tokens.issue();
        UUID id = UUID.randomUUID();
        java.time.Instant createdAt = clock.instant();
        credentials.insert(new Row(
                id,
                account.id(),
                account.scopeId(),
                integration.id(),
                tokens.hash(token),
                prefix,
                createdAt,
                null));
        return new Issued(id, integration.id(), token, integration.eventsPath(), createdAt);
    }

    @Transactional
    public void revoke(UUID accountId, UUID credentialId) {
        requireEligibleAccount(accountId);
        if (credentials.revoke(accountId, credentialId, clock.instant()) == 0) {
            throw APIException.notFound();
        }
    }

    public Principal authenticate(HookIntegration integration, String token) {
        if (token == null || !token.startsWith("arg_hook_" + integration.id() + "_")) {
            throw invalidCredential();
        }
        Row credential = credentials.activeByHash(integration.id(), tokens.hash(token))
                .orElseThrow(HookCredentialService::invalidCredential);
        AccountRow account = accounts.accountById(credential.accountId())
                .filter(HookCredentialService::eligible)
                .filter(row -> credential.scopeId().equals(row.scopeId()))
                .orElseThrow(HookCredentialService::invalidCredential);
        return new Principal(account.id(), account.scopeId());
    }

    private AccountRow requireEligibleAccount(UUID accountId) {
        return accounts.accountById(accountId)
                .filter(HookCredentialService::eligible)
                .orElseThrow(APIException::signInRequired);
    }

    private static boolean eligible(AccountRow account) {
        return "VERIFIED".equals(account.status())
                && account.emailVerifiedAt() != null
                && "ENROLLED".equals(account.mfaState())
                && account.scopeId() != null;
    }

    private static APIException invalidCredential() {
        return new APIException("HOOK_CREDENTIAL_INVALID", "The integration credential is invalid or revoked.");
    }
}
