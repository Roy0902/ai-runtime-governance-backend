package local.governance.integration;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import local.governance.api.APIException;
import local.governance.authentication.crypto.SecureTokenService;
import local.governance.integration.HookCredentialRepository.Row;
import local.governance.security.AppUserService;
import local.governance.security.AppUserService.AppUser;
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
    private final AppUserService users;
    private final SecureTokenService tokens;
    private final Clock clock;

    public HookCredentialService(
            HookCredentialRepository credentials,
            AppUserService users,
            SecureTokenService tokens,
            Clock clock) {
        this.credentials = credentials;
        this.users = users;
        this.tokens = tokens;
        this.clock = clock;
    }

    public List<Summary> list(UUID appUserId) {
        requireEligibleUser(appUserId);
        return credentials.forAccount(appUserId).stream()
                .map(row -> new Summary(
                        row.id(), row.integration(), row.tokenPrefix(), row.createdAt(), row.revokedAt()))
                .toList();
    }

    @Transactional
    public Issued issue(UUID appUserId, HookIntegration integration) {
        AppUser user = requireEligibleUser(appUserId);
        if (credentials.activeForAccount(appUserId, integration.id()).isPresent()) {
            throw new APIException("HOOK_CREDENTIAL_EXISTS", "Revoke the existing integration token before creating another.");
        }
        String prefix = "arg_hook_" + integration.id();
        String token = prefix + "_" + tokens.issue();
        UUID id = UUID.randomUUID();
        java.time.Instant createdAt = clock.instant();
        credentials.insert(new Row(
                id,
                user.id(),
                user.scopeId(),
                integration.id(),
                tokens.hash(token),
                prefix,
                createdAt,
                null));
        return new Issued(id, integration.id(), token, integration.eventsPath(), createdAt);
    }

    @Transactional
    public void revoke(UUID appUserId, UUID credentialId) {
        requireEligibleUser(appUserId);
        if (credentials.revoke(appUserId, credentialId, clock.instant()) == 0) {
            throw APIException.notFound();
        }
    }

    public Principal authenticate(HookIntegration integration, String token) {
        if (token == null || !token.startsWith("arg_hook_" + integration.id() + "_")) {
            throw invalidCredential();
        }
        Row credential = credentials.activeByHash(integration.id(), tokens.hash(token))
                .orElseThrow(HookCredentialService::invalidCredential);
        AppUser user = users.findById(credential.accountId())
                .filter(HookCredentialService::eligible)
                .filter(row -> credential.scopeId().equals(row.scopeId()))
                .orElseThrow(HookCredentialService::invalidCredential);
        return new Principal(user.id(), user.scopeId());
    }

    private AppUser requireEligibleUser(UUID appUserId) {
        AppUser user = users.findById(appUserId).orElseThrow(APIException::signInRequired);
        if (!eligible(user)) {
            throw APIException.signInRequired();
        }
        return user;
    }

    private static boolean eligible(AppUser user) {
        return "ACTIVE".equals(user.status()) && user.scopeId() != null;
    }

    private static APIException invalidCredential() {
        return new APIException("HOOK_CREDENTIAL_INVALID", "The integration credential is invalid or revoked.");
    }
}
