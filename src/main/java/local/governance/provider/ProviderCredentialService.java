package local.governance.provider;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import local.governance.api.APIException;
import local.governance.provider.ProviderCredentialRepository.Row;
import local.governance.provider.crypto.EnvelopeCipher;
import local.governance.security.AppUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderCredentialService {

    private static final int MAX_SECRET_LENGTH = 4096;
    private static final int MAX_ACCESS_KEY_LENGTH = 512;
    private static final int MAX_NAME_LENGTH = 64;

    public record Summary(
            String provider,
            String name,
            String accessKey,
            String secretHint,
            Instant createdAt,
            Instant updatedAt) {}

    public record Resolved(String accessKey, String secret) {}

    private final ProviderCredentialRepository credentials;
    private final EnvelopeCipher cipher;
    private final AppUserService users;
    private final Clock clock;

    public ProviderCredentialService(
            ProviderCredentialRepository credentials,
            EnvelopeCipher cipher,
            AppUserService users,
            Clock clock) {
        this.credentials = credentials;
        this.cipher = cipher;
        this.users = users;
        this.clock = clock;
    }

    public List<Summary> list(UUID accountId) {
        users.requireActive(accountId);
        return credentials.forAccount(accountId).stream().map(ProviderCredentialService::summary).toList();
    }

    public List<Summary> listForGroup(UUID groupId) {
        return credentials.forGroup(groupId).stream().map(ProviderCredentialService::summary).toList();
    }

    @Transactional
    public Summary save(UUID accountId, ProviderKind kind, String accessKey, String secret, String name) {
        users.requireActive(accountId);
        return saveRow(accountId, null, kind, accessKey, secret, name);
    }

    @Transactional
    public Summary saveForGroup(UUID groupId, ProviderKind kind, String accessKey, String secret, String name) {
        return saveRow(null, groupId, kind, accessKey, secret, name);
    }

    private Summary saveRow(
            UUID accountId,
            UUID groupId,
            ProviderKind kind,
            String accessKey,
            String secret,
            String name) {
        String normalizedAccess = normalizeAccessKey(accessKey);
        String normalizedSecret = normalizeSecret(secret);
        String normalizedName = normalizeName(name, kind);
        String owner = owner(accountId, groupId);

        Instant now = clock.instant();
        EnvelopeCipher.Sealed sealed = cipher.seal(owner, kind.name(), normalizedSecret);
        credentials.upsert(new Row(
                UUID.randomUUID(),
                accountId,
                kind.name(),
                normalizedName,
                normalizedAccess,
                hint(normalizedSecret),
                sealed.nonce(),
                sealed.ciphertext(),
                sealed.wrappedKey(),
                sealed.keyId(),
                now,
                now,
                0,
                groupId));
        Row saved = groupId == null
                ? credentials.find(accountId, kind.name()).orElseThrow(APIException::unavailable)
                : credentials.findForGroup(groupId, kind.name()).orElseThrow(APIException::unavailable);
        return summary(saved);
    }

    @Transactional
    public void delete(UUID accountId, ProviderKind kind) {
        users.requireActive(accountId);
        if (credentials.delete(accountId, kind.name()) == 0) {
            throw APIException.notFound();
        }
    }

    @Transactional
    public void deleteForGroup(UUID groupId, ProviderKind kind) {
        if (credentials.deleteForGroup(groupId, kind.name()) == 0) {
            throw APIException.notFound();
        }
    }

    /**
     * Returns the plaintext provider secret for server-side export. Never expose
     * this through a controller.
     */
    public String reveal(UUID accountId, ProviderKind kind) {
        Row row = credentials.find(accountId, kind.name())
                .orElseThrow(ProviderCredentialService::notConfigured);
        return open(row, "user:" + accountId);
    }

    public String revealForGroup(UUID groupId, ProviderKind kind) {
        Row row = credentials.findForGroup(groupId, kind.name())
                .orElseThrow(ProviderCredentialService::notConfigured);
        return open(row, "group:" + groupId);
    }

    public java.util.Optional<Resolved> resolve(UUID accountId, ProviderKind kind) {
        return credentials.find(accountId, kind.name())
                .map(row -> new Resolved(row.accessKey(), open(row, "user:" + accountId)));
    }

    public java.util.Optional<Resolved> resolveForGroup(UUID groupId, ProviderKind kind) {
        return credentials.findForGroup(groupId, kind.name())
                .map(row -> new Resolved(row.accessKey(), open(row, "group:" + groupId)));
    }

    private String open(Row row, String owner) {
        return cipher.open(
                owner,
                row.provider(),
                new EnvelopeCipher.Sealed(row.keyId(), row.nonce(), row.ciphertext(), row.wrappedKey()));
    }

    private static String owner(UUID accountId, UUID groupId) {
        return groupId == null ? "user:" + accountId : "group:" + groupId;
    }

    private static Summary summary(Row row) {
        return new Summary(
                row.provider(), row.name(), row.accessKey(), row.secretHint(), row.createdAt(), row.updatedAt());
    }

    private static String normalizeAccessKey(String accessKey) {
        if (accessKey == null) {
            return null;
        }
        String value = accessKey.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > MAX_ACCESS_KEY_LENGTH) {
            throw APIException.invalid("accessKey", "Access key is too long.");
        }
        return value;
    }

    private static String normalizeSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw APIException.invalid("secretKey", "Secret key is required.");
        }
        if (secret.length() > MAX_SECRET_LENGTH) {
            throw APIException.invalid("secretKey", "Secret key is too long.");
        }
        return secret;
    }

    private static String normalizeName(String name, ProviderKind kind) {
        String value = name == null || name.isBlank() ? kind.label() : name.trim();
        if (value.length() > MAX_NAME_LENGTH) {
            throw APIException.invalid("name", "Name must be at most 64 characters.");
        }
        return value;
    }

    private static String hint(String secret) {
        if (secret.length() <= 4) {
            return "****";
        }
        return "****" + secret.substring(secret.length() - 4);
    }

    private static APIException notConfigured() {
        return new APIException("PROVIDER_NOT_CONFIGURED", "This provider is not configured.");
    }
}
