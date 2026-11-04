package local.governance.gateway;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import local.governance.authentication.crypto.SecureTokenService;
import local.governance.config.GovernanceProperties;
import local.governance.gateway.GatewayConversationRepository.Row;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Correlates tool/assistant continuations with the guarded human turn that
 * started them. The key covers the stable conversation prefix up to the last
 * user message, so later assistant/tool messages do not change it.
 */
@Service
public class GatewayConversationService {

    private final GatewayConversationRepository conversations;
    private final SecureTokenService tokens;
    private final GovernanceProperties properties;
    private final Clock clock;

    public GatewayConversationService(
            GatewayConversationRepository conversations,
            SecureTokenService tokens,
            GovernanceProperties properties,
            Clock clock) {
        this.conversations = conversations;
        this.tokens = tokens;
        this.properties = properties;
        this.clock = clock;
    }

    public String key(
            UUID scopeId,
            String model,
            String canonicalPrefix,
            String clientConversationId) {
        String material = scopeId
                + "\n" + model
                + "\n" + (clientConversationId == null ? "" : clientConversationId)
                + "\n" + canonicalPrefix;
        return HexFormat.of().formatHex(tokens.sha256(material.getBytes(StandardCharsets.UTF_8)));
    }

    public String messageHash(String text) {
        return HexFormat.of().formatHex(tokens.sha256(text.getBytes(StandardCharsets.UTF_8)));
    }

    public Optional<Row> find(UUID scopeId, String conversationKey) {
        Instant now = clock.instant();
        Optional<Row> found = conversations.find(conversationKey, scopeId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Row row = found.get();
        if (!now.isBefore(row.expiresAt())) {
            conversations.delete(conversationKey, scopeId);
            return Optional.empty();
        }
        return found;
    }

    @Transactional
    public Row record(
            UUID scopeId,
            UUID traceId,
            String conversationKey,
            String guardedMessageHash,
            String outcome) {
        Instant now = clock.instant();
        Row row = new Row(
                conversationKey,
                scopeId,
                traceId,
                guardedMessageHash,
                outcome,
                "redact".equals(outcome),
                now,
                now.plusSeconds(properties.getGateway().getConversationTtlSeconds()));
        conversations.upsert(row);
        return row;
    }

    public int deleteExpired(UUID scopeId) {
        return conversations.deleteExpired(scopeId, clock.instant());
    }
}
