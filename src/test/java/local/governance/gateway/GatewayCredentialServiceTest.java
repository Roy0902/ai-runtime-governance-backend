package local.governance.gateway;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import local.governance.authentication.persistence.AuthenticationRepository;
import local.governance.authentication.persistence.AuthenticationRepository.AccountRow;
import local.governance.config.GovernanceProperties;
import local.governance.gateway.GatewayCredentialRepository.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GatewayCredentialServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    private final UUID accountId = UUID.randomUUID();
    private final UUID scopeId = UUID.randomUUID();
    private GatewayCredentialRepository credentials;
    private AuthenticationRepository accounts;
    private SecureTokenService tokens;
    private GovernanceProperties properties;
    private GatewayCredentialService service;

    @BeforeEach
    void setUp() {
        credentials = mock(GatewayCredentialRepository.class);
        accounts = mock(AuthenticationRepository.class);
        tokens = new SecureTokenService();
        properties = new GovernanceProperties();
        service = new GatewayCredentialService(
                credentials, accounts, tokens, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        when(accounts.accountById(accountId)).thenReturn(Optional.of(account("GRANTED")));
    }

    @Test
    void issuesScopedKeyAndStoresOnlyItsHash() {
        when(credentials.activeByName(accountId, "laptop")).thenReturn(Optional.empty());
        when(credentials.activeCount(accountId)).thenReturn(0L);

        GatewayCredentialService.Issued issued = service.issue(accountId, " laptop ");

        ArgumentCaptor<Row> row = ArgumentCaptor.forClass(Row.class);
        verify(credentials).insert(row.capture());
        assertTrue(issued.token().startsWith("arg_gw_"));
        assertEquals("laptop", issued.name());
        assertEquals(accountId, row.getValue().accountId());
        assertEquals(scopeId, row.getValue().scopeId());
        assertArrayEquals(tokens.hash(issued.token()), row.getValue().tokenHash());
        assertTrue(issued.token().startsWith(row.getValue().tokenPrefix()));
        assertEquals(13, row.getValue().tokenPrefix().length());
    }

    @Test
    void listingNeverReturnsTheSecret() {
        Row row = new Row(UUID.randomUUID(), accountId, scopeId, "laptop",
                new byte[32], "arg_gw_abcdef", NOW, null, null);
        when(credentials.forAccount(accountId)).thenReturn(List.of(row));

        List<GatewayCredentialService.Summary> summaries = service.list(accountId);

        assertEquals(1, summaries.size());
        assertEquals("laptop", summaries.getFirst().name());
        assertEquals("arg_gw_abcdef", summaries.getFirst().tokenPrefix());
    }

    @Test
    void refusesIssueWithoutGatewayEntitlement() {
        when(accounts.accountById(accountId)).thenReturn(Optional.of(account("REVOKED")));

        APIException exception = assertThrows(
                APIException.class,
                () -> service.issue(accountId, "laptop"));

        assertEquals("GATEWAY_NOT_ENTITLED", exception.code());
        verify(credentials, never()).insert(any());
    }

    @Test
    void refusesMoreThanTheConfiguredNumberOfActiveKeys() {
        properties.getGateway().setMaxActiveKeys(2);
        when(credentials.activeByName(accountId, "laptop")).thenReturn(Optional.empty());
        when(credentials.activeCount(accountId)).thenReturn(2L);

        APIException exception = assertThrows(
                APIException.class,
                () -> service.issue(accountId, "laptop"));

        assertEquals("GATEWAY_KEY_LIMIT", exception.code());
        verify(credentials, never()).insert(any());
    }

    @Test
    void refusesDuplicateActiveNames() {
        when(credentials.activeByName(accountId, "laptop")).thenReturn(Optional.of(
                new Row(UUID.randomUUID(), accountId, scopeId, "laptop",
                        new byte[32], "arg_gw_abcdef", NOW, null, null)));

        APIException exception = assertThrows(
                APIException.class,
                () -> service.issue(accountId, "laptop"));

        assertEquals("GATEWAY_KEY_EXISTS", exception.code());
    }

    @Test
    void rejectsUnsafeKeyNames() {
        APIException exception = assertThrows(
                APIException.class,
                () -> service.issue(accountId, "bad\nname"));

        assertEquals("REQUEST_INVALID", exception.code());
        verify(credentials, never()).insert(any());
    }

    @Test
    void authenticatesAnActiveKeyAndRefreshesItsLastUsedTime() {
        String token = "arg_gw_secret";
        Row row = new Row(UUID.randomUUID(), accountId, scopeId, "laptop",
                tokens.hash(token), "arg_gw_secret", NOW, null, null);
        when(credentials.activeByHash(tokens.hash(token))).thenReturn(Optional.of(row));

        GatewayCredentialService.Principal principal = service.authenticate(token);

        assertEquals(accountId, principal.accountId());
        assertEquals(scopeId, principal.scopeId());
        verify(credentials).markUsed(row.id(), NOW);
    }

    @Test
    void rejectsARevokedOrUnknownKey() {
        when(credentials.activeByHash(any())).thenReturn(Optional.empty());

        APIException exception = assertThrows(
                APIException.class,
                () -> service.authenticate("arg_gw_unknown"));

        assertEquals("GATEWAY_CREDENTIAL_INVALID", exception.code());
    }

    @Test
    void rejectsAKeyWhoseAccountLostGatewayPermission() {
        String token = "arg_gw_secret";
        Row row = new Row(UUID.randomUUID(), accountId, scopeId, "laptop",
                tokens.hash(token), "arg_gw_secret", NOW, null, null);
        when(credentials.activeByHash(tokens.hash(token))).thenReturn(Optional.of(row));
        when(accounts.accountById(accountId)).thenReturn(Optional.of(account("REVOKED")));

        APIException exception = assertThrows(
                APIException.class,
                () -> service.authenticate(token));

        assertEquals("GATEWAY_NOT_ENTITLED", exception.code());
        verify(credentials, never()).markUsed(any(), any());
    }

    @Test
    void revokeReportsMissingKeysAsNotFound() {
        when(credentials.revoke(accountId, UUID.randomUUID(), NOW)).thenReturn(0);

        APIException exception = assertThrows(
                APIException.class,
                () -> service.revoke(accountId, UUID.randomUUID()));

        assertEquals("NOT_FOUND", exception.code());
    }

    private AccountRow account(String entitlement) {
        return new AccountRow(
                accountId,
                "person@example.test",
                "hash",
                NOW,
                "VERIFIED",
                "ENROLLED",
                scopeId,
                entitlement,
                NOW,
                NOW,
                null,
                null,
                0);
    }
}
