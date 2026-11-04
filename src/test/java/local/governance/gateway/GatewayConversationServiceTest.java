package local.governance.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import local.governance.authentication.crypto.SecureTokenService;
import local.governance.config.GovernanceProperties;
import local.governance.gateway.GatewayConversationRepository.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GatewayConversationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    private final UUID scopeId = UUID.randomUUID();
    private GatewayConversationRepository repository;
    private GovernanceProperties properties;
    private GatewayConversationService service;

    @BeforeEach
    void setUp() {
        repository = mock(GatewayConversationRepository.class);
        properties = new GovernanceProperties();
        service = new GatewayConversationService(
                repository, new SecureTokenService(), properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void keyStaysStableWhenOnlyLaterAssistantAndToolMessagesChange() {
        String prefix = "[{\"content\":\"hi\",\"role\":\"user\"}]";

        String first = service.key(scopeId, "gpt-4o", prefix, null);
        String second = service.key(scopeId, "gpt-4o", prefix, null);

        assertEquals(first, second);
        assertNotEquals(first, service.key(scopeId, "gpt-4o-mini", prefix, null));
        assertNotEquals(first, service.key(UUID.randomUUID(), "gpt-4o", prefix, null));
        assertNotEquals(first, service.key(scopeId, "gpt-4o", prefix, "session-a"));
    }

    @Test
    void findsALiveRecordAndDeletesAnExpiredOne() {
        String key = "key";
        Row live = new Row(key, scopeId, UUID.randomUUID(), "hash", "allow", false,
                NOW.minusSeconds(10), NOW.plusSeconds(60));
        when(repository.find(key, scopeId)).thenReturn(Optional.of(live));

        assertTrue(service.find(scopeId, key).isPresent());

        Row expired = new Row(key, scopeId, UUID.randomUUID(), "hash", "allow", false,
                NOW.minusSeconds(120), NOW.minusSeconds(1));
        when(repository.find(key, scopeId)).thenReturn(Optional.of(expired));

        assertTrue(service.find(scopeId, key).isEmpty());
        org.mockito.Mockito.verify(repository).delete(key, scopeId);
    }

    @Test
    void recordsTheConfiguredExpiry() {
        properties.getGateway().setConversationTtlSeconds(600);

        Row row = service.record(scopeId, UUID.randomUUID(), "key", "hash", "redact");

        assertEquals(NOW.plusSeconds(600), row.expiresAt());
        assertEquals("redact", row.guardOutcome());
        org.mockito.Mockito.verify(repository).upsert(row);
    }

    @Test
    void messageHashChangesWithTheText() {
        assertNotEquals(service.messageHash("hello"), service.messageHash("hello world"));
        assertEquals(service.messageHash("hello"), service.messageHash("hello"));
    }
}
