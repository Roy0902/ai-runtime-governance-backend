package local.governance.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import local.governance.api.APIException;
import local.governance.authentication.crypto.SecureTokenService;
import local.governance.integration.HookCredentialRepository.Row;
import local.governance.security.AppUserService;
import local.governance.security.AppUserService.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HookCredentialServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    private final UUID accountId = UUID.randomUUID();
    private final UUID scopeId = UUID.randomUUID();
    private HookCredentialRepository credentials;
    private AppUserService users;
    private SecureTokenService tokens;
    private HookCredentialService service;

    @BeforeEach
    void setUp() {
        credentials = mock(HookCredentialRepository.class);
        users = mock(AppUserService.class);
        tokens = new SecureTokenService();
        service = new HookCredentialService(
                credentials, users, tokens, Clock.fixed(NOW, ZoneOffset.UTC));
        when(users.findById(accountId)).thenReturn(Optional.of(eligibleUser("ACTIVE", scopeId)));
    }

    @Test
    void issuesScopedOneTimeCredentialAndStoresOnlyItsHash() {
        when(credentials.activeForAccount(accountId, "langchain4j")).thenReturn(Optional.empty());

        HookCredentialService.Issued issued = service.issue(accountId, HookIntegration.LANGCHAIN4J);

        ArgumentCaptor<Row> row = ArgumentCaptor.forClass(Row.class);
        verify(credentials).insert(row.capture());
        assertTrue(issued.token().startsWith("arg_hook_langchain4j_"));
        assertEquals("/api/v1/hooks/langchain4j/events", issued.eventsPath());
        assertEquals(accountId, row.getValue().accountId());
        assertEquals(scopeId, row.getValue().scopeId());
        assertArrayEquals(tokens.hash(issued.token()), row.getValue().tokenHash());
    }

    @Test
    void doesNotReturnCredentialSecretWhenListing() {
        Row row = new Row(UUID.randomUUID(), accountId, scopeId, "langfuse",
                new byte[32], "arg_hook_langfuse", NOW, null);
        when(credentials.forAccount(accountId)).thenReturn(List.of(row));

        List<HookCredentialService.Summary> summaries = service.list(accountId);

        assertEquals(1, summaries.size());
        assertEquals("langfuse", summaries.getFirst().integration());
        assertEquals("arg_hook_langfuse", summaries.getFirst().tokenPrefix());
    }

    @Test
    void eventCredentialCannotCrossIntegrationOrScope() {
        Row credential = new Row(UUID.randomUUID(), accountId, UUID.randomUUID(), "langchain4j",
                tokens.hash("arg_hook_langchain4j_secret"), "arg_hook_langchain4j", NOW, null);
        when(credentials.activeByHash("langfuse", tokens.hash("arg_hook_langchain4j_secret")))
                .thenReturn(Optional.empty());
        when(credentials.activeByHash("langchain4j", tokens.hash("arg_hook_langchain4j_secret")))
                .thenReturn(Optional.of(credential));

        APIException wrongIntegration = assertThrows(
                APIException.class,
                () -> service.authenticate(HookIntegration.LANGFUSE, "arg_hook_langchain4j_secret"));
        assertEquals("HOOK_CREDENTIAL_INVALID", wrongIntegration.code());

        APIException wrongScope = assertThrows(
                APIException.class,
                () -> service.authenticate(HookIntegration.LANGCHAIN4J, "arg_hook_langchain4j_secret"));
        assertEquals("HOOK_CREDENTIAL_INVALID", wrongScope.code());
    }

    @Test
    void refusesCredentialIssuanceForAnAccountWithoutCompletedEnrollment() {
        when(users.findById(accountId)).thenReturn(Optional.of(eligibleUser("DISABLED", scopeId)));

        APIException exception = assertThrows(
                APIException.class,
                () -> service.issue(accountId, HookIntegration.LANGFUSE));

        assertEquals("SIGN_IN_REQUIRED", exception.code());
        verify(credentials, org.mockito.Mockito.never()).insert(any());
    }

    private AppUser eligibleUser(String status, UUID scope) {
        return new AppUser(
                accountId,
                "keycloak-subject",
                "person@example.test",
                "Person",
                status,
                "ABSENT",
                scope,
                NOW,
                NOW,
                null,
                0);
    }
}
