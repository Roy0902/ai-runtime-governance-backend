package local.governance.group;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import local.governance.api.APIException;
import local.governance.authentication.crypto.SecureTokenService;
import local.governance.group.GroupRepository.GroupRow;
import local.governance.group.GroupRepository.InviteRow;
import local.governance.group.GroupRepository.MemberRow;
import local.governance.security.AppUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GroupService {

    private static final String TOKEN_PREFIX = "arg_join_";
    private static final int MAX_NAME_LENGTH = 80;
    private static final int MAX_INVITE_HOURS = 24 * 365;

    public record GroupSummary(
            UUID id,
            String name,
            UUID ownerUserId,
            String role,
            long memberCount,
            Instant createdAt) {}

    public record Member(
            UUID userId,
            String email,
            String displayName,
            String role,
            Instant joinedAt) {}

    public record Invite(
            UUID id,
            String tokenPrefix,
            Instant createdAt,
            Instant expiresAt,
            int maxUses,
            int uses,
            Instant revokedAt) {}

    public record GroupDetail(
            UUID id,
            String name,
            UUID ownerUserId,
            String role,
            List<Member> members,
            List<Invite> invites) {}

    public record IssuedInvite(UUID id, String token, String tokenPrefix, Instant expiresAt, int maxUses) {}

    private final GroupRepository groups;
    private final AppUserService users;
    private final SecureTokenService tokens;
    private final local.governance.gateway.GatewayCredentialRepository credentials;
    private final Clock clock;

    public GroupService(
            GroupRepository groups,
            AppUserService users,
            SecureTokenService tokens,
            local.governance.gateway.GatewayCredentialRepository credentials,
            Clock clock) {
        this.groups = groups;
        this.users = users;
        this.tokens = tokens;
        this.credentials = credentials;
        this.clock = clock;
    }

    @Transactional
    public GroupSummary create(UUID accountId, String name) {
        users.requireActive(accountId);
        String normalized = normalizeName(name);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        groups.insertGroup(new GroupRow(id, normalized, accountId, now, now, 0));
        groups.insertMember(id, accountId, "OWNER", now);
        return new GroupSummary(id, normalized, accountId, "OWNER", 1, now);
    }

    public List<GroupSummary> list(UUID accountId) {
        users.requireActive(accountId);
        return groups.forUser(accountId).stream()
                .map(group -> new GroupSummary(
                        group.id(),
                        group.name(),
                        group.ownerUserId(),
                        groups.role(group.id(), accountId).orElse("MEMBER"),
                        groups.memberCount(group.id()),
                        group.createdAt()))
                .toList();
    }

    public GroupDetail detail(UUID accountId, UUID groupId) {
        String role = requireMember(groupId, accountId);
        GroupRow group = requireGroup(groupId);
        List<Member> members = groups.members(groupId).stream()
                .map(GroupService::member)
                .toList();
        List<Invite> invites = "OWNER".equals(role)
                ? groups.invites(groupId).stream().map(GroupService::invite).toList()
                : List.of();
        return new GroupDetail(group.id(), group.name(), group.ownerUserId(), role, members, invites);
    }

    @Transactional
    public IssuedInvite createInvite(UUID accountId, UUID groupId, Integer expiresInHours, Integer maxUses) {
        requireOwner(groupId, accountId);
        int hours = expiresInHours == null ? 24 * 7 : expiresInHours;
        if (hours < 1 || hours > MAX_INVITE_HOURS) {
            throw APIException.invalid("expiresInHours", "Expiry must be between 1 and 8760 hours.");
        }
        int uses = maxUses == null ? 0 : maxUses;
        if (uses < 0 || uses > 10_000) {
            throw APIException.invalid("maxUses", "Use 0 for unlimited or a number up to 10000.");
        }
        String token = TOKEN_PREFIX + tokens.issue();
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(Duration.ofHours(hours));
        String prefix = token.substring(0, Math.min(token.length(), TOKEN_PREFIX.length() + 6));
        groups.insertInvite(new InviteRow(
                id, groupId, tokens.hash(token), prefix, accountId, now, expiresAt, uses, 0, null));
        return new IssuedInvite(id, token, prefix, expiresAt, uses);
    }

    @Transactional
    public void revokeInvite(UUID accountId, UUID groupId, UUID inviteId) {
        requireOwner(groupId, accountId);
        if (groups.revokeInvite(groupId, inviteId, clock.instant()) == 0) {
            throw APIException.notFound();
        }
    }

    @Transactional
    public void removeMember(UUID accountId, UUID groupId, UUID userId) {
        GroupRow group = requireGroup(groupId);
        requireOwner(groupId, accountId);
        if (group.ownerUserId().equals(userId)) {
            throw APIException.invalid("userId", "The owner cannot be removed.");
        }
        if (groups.removeMember(groupId, userId) == 0) {
            throw APIException.notFound();
        }
        credentials.revokeForGroupMember(groupId, userId, clock.instant());
    }

    @Transactional
    public GroupSummary join(UUID accountId, String code) {
        users.requireActive(accountId);
        if (code == null || !code.startsWith(TOKEN_PREFIX)) {
            throw invalidInvite();
        }
        InviteRow invite = groups.activeInvite(tokens.hash(code.trim()))
                .orElseThrow(GroupService::invalidInvite);
        Instant now = clock.instant();
        if (invite.expiresAt() != null && invite.expiresAt().isBefore(now)) {
            throw invalidInvite();
        }
        if (invite.maxUses() > 0 && invite.uses() >= invite.maxUses()) {
            throw invalidInvite();
        }

        boolean alreadyMember = groups.isMember(invite.groupId(), accountId);
        if (!alreadyMember) {
            if (groups.consumeInvite(invite.id(), now) == 0) {
                throw invalidInvite();
            }
            groups.insertMember(invite.groupId(), accountId, "MEMBER", now);
        }
        GroupRow group = requireGroup(invite.groupId());
        return new GroupSummary(
                group.id(),
                group.name(),
                group.ownerUserId(),
                groups.role(group.id(), accountId).orElse("MEMBER"),
                groups.memberCount(group.id()),
                group.createdAt());
    }

    public String requireMember(UUID groupId, UUID accountId) {
        return groups.role(groupId, accountId)
                .orElseThrow(() -> new APIException("GROUP_ACCESS_DENIED", "You are not a member of this group."));
    }

    public void requireOwner(UUID groupId, UUID accountId) {
        String role = groups.role(groupId, accountId)
                .orElseThrow(() -> new APIException("GROUP_ACCESS_DENIED", "You are not a member of this group."));
        if (!"OWNER".equals(role)) {
            throw new APIException("GROUP_ACCESS_DENIED", "Only the group owner can do that.");
        }
    }

    public boolean isMember(UUID groupId, UUID accountId) {
        return groups.isMember(groupId, accountId);
    }

    private GroupRow requireGroup(UUID groupId) {
        return groups.findGroup(groupId).orElseThrow(APIException::notFound);
    }

    private static Member member(MemberRow row) {
        return new Member(row.userId(), row.email(), row.displayName(), row.role(), row.joinedAt());
    }

    private static Invite invite(InviteRow row) {
        return new Invite(
                row.id(), row.tokenPrefix(), row.createdAt(), row.expiresAt(),
                row.maxUses(), row.uses(), row.revokedAt());
    }

    private static String normalizeName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty() || value.length() > MAX_NAME_LENGTH) {
            throw APIException.invalid("name", "Group name must be 1 to 80 characters.");
        }
        return value;
    }

    private static APIException invalidInvite() {
        return new APIException("GROUP_INVITE_INVALID", "This invite code is invalid, expired, or already used up.");
    }
}
