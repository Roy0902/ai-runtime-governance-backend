package local.governance.group;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import local.governance.api.APIException;
import local.governance.authentication.crypto.SecureTokenService;
import local.governance.authentication.persistence.AuthenticationRepository.AccountRow;
import local.governance.gateway.GatewayCredentialRepository;
import local.governance.group.GroupRepository.GroupRow;
import local.governance.group.GroupRepository.InviteRow;
import local.governance.security.AccountContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GroupServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

    private GroupRepository groups;
    private AccountContextService users;
    private GatewayCredentialRepository credentials;
    private SecureTokenService tokens;
    private GroupService service;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        groups = mock(GroupRepository.class);
        users = mock(AccountContextService.class);
        credentials = mock(GatewayCredentialRepository.class);
        tokens = new SecureTokenService();
        service = new GroupService(groups, users, tokens, credentials, Clock.fixed(NOW, ZoneOffset.UTC));
        when(users.requireActive(any())).thenAnswer(invocation -> user(invocation.getArgument(0)));
    }

    @Test
    void createsGroupWithOwnerMembership() {
        GroupService.GroupSummary summary = service.create(ownerId, " Platform ");

        verify(groups).insertGroup(any());
        verify(groups).insertMember(any(), eq(ownerId), eq("OWNER"), eq(NOW));
        assertEquals("Platform", summary.name());
        assertEquals("OWNER", summary.role());
    }

    @Test
    void createsAOneTimeInviteAndStoresOnlyItsHash() {
        when(groups.role(groupId, ownerId)).thenReturn(Optional.of("OWNER"));

        GroupService.IssuedInvite invite = service.createInvite(ownerId, groupId, 24, 0);

        assertTrue(invite.token().startsWith("arg_join_"));
        ArgumentCaptor<InviteRow> row = ArgumentCaptor.forClass(InviteRow.class);
        verify(groups).insertInvite(row.capture());
        assertEquals("arg_join_", invite.token().substring(0, 9));
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                tokens.hash(invite.token()), row.getValue().tokenHash());
        assertEquals(NOW.plusSeconds(86_400), row.getValue().expiresAt());
    }

    @Test
    void onlyTheOwnerCanInvite() {
        when(groups.role(groupId, memberId)).thenReturn(Optional.of("MEMBER"));

        APIException exception = assertThrows(
                APIException.class,
                () -> service.createInvite(memberId, groupId, 24, 0));

        assertEquals("GROUP_ACCESS_DENIED", exception.code());
        verify(groups, never()).insertInvite(any());
    }

    @Test
    void joiningConsumesTheInviteAndAddsTheMember() {
        String code = "arg_join_" + tokens.issue();
        InviteRow invite = new InviteRow(
                UUID.randomUUID(), groupId, tokens.hash(code), "arg_join_abc",
                ownerId, NOW.minusSeconds(60), NOW.plusSeconds(3600), 1, 0, null);
        when(groups.activeInvite(tokens.hash(code))).thenReturn(Optional.of(invite));
        when(groups.consumeInvite(invite.id(), NOW)).thenReturn(1);
        when(groups.findGroup(groupId)).thenReturn(Optional.of(
                new GroupRow(groupId, "Platform", ownerId, NOW, NOW, 0)));
        when(groups.role(groupId, memberId)).thenReturn(Optional.of("MEMBER"));
        when(groups.memberCount(groupId)).thenReturn(2L);

        GroupService.GroupSummary summary = service.join(memberId, code);

        verify(groups).insertMember(groupId, memberId, "MEMBER", NOW);
        assertEquals("Platform", summary.name());
        assertEquals("MEMBER", summary.role());
    }

    @Test
    void joiningRejectsAnExpiredInvite() {
        String code = "arg_join_" + tokens.issue();
        InviteRow invite = new InviteRow(
                UUID.randomUUID(), groupId, tokens.hash(code), "arg_join_abc",
                ownerId, NOW.minusSeconds(7200), NOW.minusSeconds(3600), 0, 0, null);
        when(groups.activeInvite(tokens.hash(code))).thenReturn(Optional.of(invite));

        APIException exception = assertThrows(APIException.class, () -> service.join(memberId, code));

        assertEquals("GROUP_INVITE_INVALID", exception.code());
        verify(groups, never()).insertMember(any(), any(), any(), any());
    }

    @Test
    void removingAMemberRevokesTheirGroupKeys() {
        when(groups.findGroup(groupId)).thenReturn(Optional.of(
                new GroupRow(groupId, "Platform", ownerId, NOW, NOW, 0)));
        when(groups.role(groupId, ownerId)).thenReturn(Optional.of("OWNER"));
        when(groups.removeMember(groupId, memberId)).thenReturn(1);

        service.removeMember(ownerId, groupId, memberId);

        verify(credentials).revokeForGroupMember(groupId, memberId, NOW);
    }

    @Test
    void theOwnerCannotBeRemoved() {
        when(groups.findGroup(groupId)).thenReturn(Optional.of(
                new GroupRow(groupId, "Platform", ownerId, NOW, NOW, 0)));
        when(groups.role(groupId, ownerId)).thenReturn(Optional.of("OWNER"));

        APIException exception = assertThrows(
                APIException.class,
                () -> service.removeMember(ownerId, groupId, ownerId));

        assertEquals("REQUEST_INVALID", exception.code());
        verify(credentials, never()).revokeForGroupMember(any(), any(), any());
    }

    private AccountRow user(UUID id) {
        return new AccountRow(
                id,
                "user@example.test",
                "hash",
                NOW,
                "VERIFIED",
                "ENROLLED",
                UUID.randomUUID(),
                "GRANTED",
                NOW,
                NOW,
                null,
                null,
                0);
    }
}
