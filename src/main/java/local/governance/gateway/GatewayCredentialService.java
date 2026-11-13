package local.governance.gateway;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import local.governance.api.APIException;
import local.governance.authentication.crypto.SecureTokenService;
import local.governance.config.GovernanceProperties;
import local.governance.gateway.GatewayCredentialRepository.Row;
import local.governance.group.GroupRepository;
import local.governance.security.AppUserService;
import local.governance.security.AppUserService.AppUser;
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
            java.time.Instant revokedAt,
            UUID groupId) {}

    public record Issued(
            UUID id,
            String name,
            String token,
            java.time.Instant createdAt) {}

    public record Principal(
            UUID credentialId,
            UUID accountId,
            UUID scopeId,
            UUID groupId,
            String email,
            String displayName,
            String groupName) {

        public Principal(UUID credentialId, UUID accountId, UUID scopeId, UUID groupId) {
            this(credentialId, accountId, scopeId, groupId, null, null, null);
        }
    }

    private final GatewayCredentialRepository credentials;
    private final AppUserService users;
    private final SecureTokenService tokens;
    private final GovernanceProperties properties;
    private final GroupRepository groups;
    private final Clock clock;

    public GatewayCredentialService(
            GatewayCredentialRepository credentials,
            AppUserService users,
            SecureTokenService tokens,
            GovernanceProperties properties,
            GroupRepository groups,
            Clock clock) {
        this.credentials = credentials;
        this.users = users;
        this.tokens = tokens;
        this.properties = properties;
        this.groups = groups;
        this.clock = clock;
    }

    public List<Summary> list(UUID appUserId) {
        users.requireActive(appUserId);
        return credentials.forAccount(appUserId).stream()
                .map(row -> new Summary(
                        row.id(),
                        row.name(),
                        row.tokenPrefix(),
                        row.createdAt(),
                        row.lastUsedAt(),
                        row.revokedAt(),
                        row.groupId()))
                .toList();
    }

    @Transactional
    public Issued issue(UUID appUserId, String name) {
        return issue(appUserId, null, name);
    }

    @Transactional
    public Issued issue(UUID appUserId, UUID groupId, String name) {
        AppUser user;
        if (groupId == null) {
            user = users.requireGatewayEntitled(appUserId);
        } else {
            user = users.requireActive(appUserId);
            if (!groups.isMember(groupId, appUserId)) {
                throw new APIException("GROUP_ACCESS_DENIED", "You are not a member of this group.");
            }
            if (user.scopeId() == null) {
                throw new APIException("GATEWAY_NOT_ENTITLED", "This account has no private scope.");
            }
        }
        String normalized = normalizeName(name);
        if (credentials.activeByName(appUserId, normalized).isPresent()) {
            throw new APIException("GATEWAY_KEY_EXISTS", "Revoke the existing key with that name first.");
        }
        if (credentials.activeCount(appUserId) >= properties.getGateway().getMaxActiveKeys()) {
            throw new APIException("GATEWAY_KEY_LIMIT", "Revoke an existing key before creating another.");
        }

        String token = TOKEN_PREFIX + tokens.issue();
        UUID id = UUID.randomUUID();
        java.time.Instant createdAt = clock.instant();
        int prefixLength = Math.min(token.length(), TOKEN_PREFIX.length() + 6);
        credentials.insert(new Row(
                id,
                user.id(),
                user.scopeId(),
                normalized,
                tokens.hash(token),
                token.substring(0, prefixLength),
                createdAt,
                null,
                null,
                groupId));
        return new Issued(id, normalized, token, createdAt);
    }

    @Transactional
    public void revoke(UUID appUserId, UUID credentialId) {
        users.requireActive(appUserId);
        if (credentials.revoke(appUserId, credentialId, clock.instant()) == 0) {
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
        AppUser user = users.findById(credential.accountId())
                .filter(row -> "ACTIVE".equals(row.status()))
                .filter(row -> credential.scopeId().equals(row.scopeId()))
                .orElseThrow(GatewayCredentialService::invalidCredential);
        if (credential.groupId() == null) {
            if (!"GRANTED".equals(user.gatewayEntitlement())) {
                throw new APIException(
                        "GATEWAY_NOT_ENTITLED",
                        "This account is not permitted to use the model gateway.");
            }
        } else if (!groups.isMember(credential.groupId(), user.id())) {
            throw new APIException("GROUP_ACCESS_DENIED", "You are not a member of this group.");
        }
        credentials.markUsed(credential.id(), clock.instant());
        String groupName = credential.groupId() == null
                ? null
                : groups.findGroup(credential.groupId()).map(row -> row.name()).orElse(null);
        return new Principal(
                credential.id(),
                user.id(),
                user.scopeId(),
                credential.groupId(),
                user.email(),
                user.displayName(),
                groupName);
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
