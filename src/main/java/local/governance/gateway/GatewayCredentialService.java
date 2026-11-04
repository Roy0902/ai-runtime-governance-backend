package local.governance.gateway;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import local.governance.api.APIException;
import local.governance.authentication.crypto.SecureTokenService;
import local.governance.authentication.persistence.AuthenticationRepository;
import local.governance.authentication.persistence.AuthenticationRepository.AccountRow;
import local.governance.config.GovernanceProperties;
import local.governance.gateway.GatewayCredentialRepository.Row;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GatewayCredentialService {

    public static final String TOKEN_PREFIX = "arg_gw_";
    private static final int MAX_NAME_LENGTH = 64;

    public record Summary(
            UUID id,
            String name,
            String tokenPrefix,
            java.time.Instant createdAt,
            java.time.Instant lastUsedAt,
            java.time.Instant revokedAt) {}

    public record Issued(
            UUID id,
            String name,
            String token,
            java.time.Instant createdAt) {}

    public record Principal(UUID credentialId, UUID accountId, UUID scopeId) {}

    private final GatewayCredentialRepository credentials;
    private final AuthenticationRepository accounts;
    private final SecureTokenService tokens;
    private final GovernanceProperties properties;
    private final Clock clock;

    public GatewayCredentialService(
            GatewayCredentialRepository credentials,
            AuthenticationRepository accounts,
            SecureTokenService tokens,
            GovernanceProperties properties,
            Clock clock) {
        this.credentials = credentials;
        this.accounts = accounts;
        this.tokens = tokens;
        this.properties = properties;
        this.clock = clock;
    }

    public List<Summary> list(UUID accountId) {
        requireEnrolledAccount(accountId);
        return credentials.forAccount(accountId).stream()
                .map(row -> new Summary(
                        row.id(),
                        row.name(),
                        row.tokenPrefix(),
                        row.createdAt(),
                        row.lastUsedAt(),
                        row.revokedAt()))
                .toList();
    }

    @Transactional
    public Issued issue(UUID accountId, String name) {
        AccountRow account = requireGatewayAccount(accountId);
        String normalized = normalizeName(name);
        if (credentials.activeByName(accountId, normalized).isPresent()) {
            throw new APIException("GATEWAY_KEY_EXISTS", "Revoke the existing key with that name first.");
        }
        if (credentials.activeCount(accountId) >= properties.getGateway().getMaxActiveKeys()) {
            throw new APIException("GATEWAY_KEY_LIMIT", "Revoke an existing key before creating another.");
        }

        String token = TOKEN_PREFIX + tokens.issue();
        UUID id = UUID.randomUUID();
        java.time.Instant createdAt = clock.instant();
        int prefixLength = Math.min(token.length(), TOKEN_PREFIX.length() + 6);
        credentials.insert(new Row(
                id,
                account.id(),
                account.scopeId(),
                normalized,
                tokens.hash(token),
                token.substring(0, prefixLength),
                createdAt,
                null,
                null));
        return new Issued(id, normalized, token, createdAt);
    }

    @Transactional
    public void revoke(UUID accountId, UUID credentialId) {
        requireEnrolledAccount(accountId);
        if (credentials.revoke(accountId, credentialId, clock.instant()) == 0) {
            throw APIException.notFound();
        }
    }

    @Transactional
    public Principal authenticate(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            throw invalidCredential();
        }
        Row credential = credentials.activeByHash(tokens.hash(token))
                .orElseThrow(GatewayCredentialService::invalidCredential);
        AccountRow account = accounts.accountById(credential.accountId())
                .filter(GatewayCredentialService::enrolled)
                .filter(row -> credential.scopeId().equals(row.scopeId()))
                .orElseThrow(GatewayCredentialService::invalidCredential);
        if (!"GRANTED".equals(account.gatewayEntitlement())) {
            throw new APIException(
                    "GATEWAY_NOT_ENTITLED",
                    "This account is not permitted to use the model gateway.");
        }
        credentials.markUsed(credential.id(), clock.instant());
        return new Principal(credential.id(), account.id(), account.scopeId());
    }

    private AccountRow requireEnrolledAccount(UUID accountId) {
        return accounts.accountById(accountId)
                .filter(GatewayCredentialService::enrolled)
                .orElseThrow(APIException::signInRequired);
    }

    private AccountRow requireGatewayAccount(UUID accountId) {
        AccountRow account = requireEnrolledAccount(accountId);
        if (!"GRANTED".equals(account.gatewayEntitlement())) {
            throw new APIException(
                    "GATEWAY_NOT_ENTITLED",
                    "Model-gateway permission must be granted before a key can be created.");
        }
        return account;
    }

    private static boolean enrolled(AccountRow account) {
        return "VERIFIED".equals(account.status())
                && account.emailVerifiedAt() != null
                && "ENROLLED".equals(account.mfaState())
                && account.scopeId() != null;
    }

    private static String normalizeName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()
                || value.length() > MAX_NAME_LENGTH
                || !value.matches("[A-Za-z0-9][A-Za-z0-9 ._-]*")) {
            throw APIException.invalid(
                    "name",
                    "Use 1-64 letters, digits, spaces, dots, underscores or hyphens.");
        }
        return value;
    }

    private static APIException invalidCredential() {
        return new APIException(
                "GATEWAY_CREDENTIAL_INVALID",
                "The gateway key is invalid or revoked.");
    }
}
