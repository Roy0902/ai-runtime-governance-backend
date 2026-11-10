package local.governance.group;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class GroupRepository {

    public record GroupRow(
            UUID id,
            String name,
            UUID ownerUserId,
            Instant createdAt,
            Instant updatedAt,
            long version) {}

    public record MemberRow(
            UUID userId,
            String email,
            String displayName,
            String role,
            Instant joinedAt) {}

    public record InviteRow(
            UUID id,
            UUID groupId,
            byte[] tokenHash,
            String tokenPrefix,
            UUID createdBy,
            Instant createdAt,
            Instant expiresAt,
            int maxUses,
            int uses,
            Instant revokedAt) {}

    public record Membership(UUID groupId, UUID userId, String role) {}

    private final JdbcClient jdbc;

    public GroupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public void insertGroup(GroupRow group) {
        jdbc.sql("""
                INSERT INTO app_group (id, name, owner_user_id, created_at, updated_at, version)
                VALUES (:id, :name, :owner, :createdAt, :updatedAt, 0)
                """)
                .param("id", group.id())
                .param("name", group.name())
                .param("owner", group.ownerUserId())
                .param("createdAt", ts(group.createdAt()))
                .param("updatedAt", ts(group.updatedAt()))
                .update();
    }

    public void insertMember(UUID groupId, UUID userId, String role, Instant joinedAt) {
        jdbc.sql("""
                INSERT INTO group_member (group_id, user_id, role, joined_at)
                VALUES (:group, :user, :role, :joinedAt)
                ON CONFLICT (group_id, user_id) DO NOTHING
                """)
                .param("group", groupId)
                .param("user", userId)
                .param("role", role)
                .param("joinedAt", ts(joinedAt))
                .update();
    }

    public Optional<GroupRow> findGroup(UUID groupId) {
        return jdbc.sql("SELECT * FROM app_group WHERE id = :id")
                .param("id", groupId)
                .query(GroupRepository::mapGroup)
                .optional();
    }

    public boolean isMember(UUID groupId, UUID userId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM group_member WHERE group_id = :group AND user_id = :user
                """)
                .param("group", groupId)
                .param("user", userId)
                .query(Long.class)
                .single() > 0;
    }

    public Optional<String> role(UUID groupId, UUID userId) {
        return jdbc.sql("""
                SELECT role FROM group_member WHERE group_id = :group AND user_id = :user
                """)
                .param("group", groupId)
                .param("user", userId)
                .query(String.class)
                .optional();
    }

    public List<GroupRow> forUser(UUID userId) {
        return jdbc.sql("""
                SELECT g.* FROM app_group g
                JOIN group_member m ON m.group_id = g.id
                WHERE m.user_id = :user
                ORDER BY g.created_at DESC
                """)
                .param("user", userId)
                .query(GroupRepository::mapGroup)
                .list();
    }

    public List<MemberRow> members(UUID groupId) {
        return jdbc.sql("""
                SELECT u.id AS user_id, u.email, u.display_name, m.role, m.joined_at
                FROM group_member m
                JOIN app_user u ON u.id = m.user_id
                WHERE m.group_id = :group
                ORDER BY m.joined_at, u.email
                """)
                .param("group", groupId)
                .query(GroupRepository::mapMember)
                .list();
    }

    public long memberCount(UUID groupId) {
        return jdbc.sql("SELECT COUNT(*) FROM group_member WHERE group_id = :group")
                .param("group", groupId)
                .query(Long.class)
                .single();
    }

    public int removeMember(UUID groupId, UUID userId) {
        return jdbc.sql("DELETE FROM group_member WHERE group_id = :group AND user_id = :user")
                .param("group", groupId)
                .param("user", userId)
                .update();
    }

    public void insertInvite(InviteRow invite) {
        jdbc.sql("""
                INSERT INTO group_invite (
                    id, group_id, token_hash, token_prefix, created_by,
                    created_at, expires_at, max_uses, uses)
                VALUES (
                    :id, :group, :hash, :prefix, :createdBy,
                    :createdAt, :expiresAt, :maxUses, 0)
                """)
                .param("id", invite.id())
                .param("group", invite.groupId())
                .param("hash", invite.tokenHash())
                .param("prefix", invite.tokenPrefix())
                .param("createdBy", invite.createdBy())
                .param("createdAt", ts(invite.createdAt()))
                .param("expiresAt", ts(invite.expiresAt()))
                .param("maxUses", invite.maxUses())
                .update();
    }

    public Optional<InviteRow> activeInvite(byte[] tokenHash) {
        return jdbc.sql("""
                SELECT * FROM group_invite
                WHERE token_hash = :hash AND revoked_at IS NULL
                """)
                .param("hash", tokenHash)
                .query(GroupRepository::mapInvite)
                .optional();
    }

    public List<InviteRow> invites(UUID groupId) {
        return jdbc.sql("""
                SELECT * FROM group_invite
                WHERE group_id = :group
                ORDER BY created_at DESC
                """)
                .param("group", groupId)
                .query(GroupRepository::mapInvite)
                .list();
    }

    public int consumeInvite(UUID inviteId, Instant now) {
        return jdbc.sql("""
                UPDATE group_invite
                SET uses = uses + 1
                WHERE id = :id AND revoked_at IS NULL
                  AND (expires_at IS NULL OR expires_at > :now)
                  AND (max_uses = 0 OR uses < max_uses)
                """)
                .param("id", inviteId)
                .param("now", ts(now))
                .update();
    }

    public int revokeInvite(UUID groupId, UUID inviteId, Instant now) {
        return jdbc.sql("""
                UPDATE group_invite SET revoked_at = :now
                WHERE group_id = :group AND id = :id AND revoked_at IS NULL
                """)
                .param("group", groupId)
                .param("id", inviteId)
                .param("now", ts(now))
                .update();
    }

    private static GroupRow mapGroup(ResultSet rs, int rowNum) throws SQLException {
        return new GroupRow(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getObject("owner_user_id", UUID.class),
                instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("updated_at", OffsetDateTime.class)),
                rs.getLong("version"));
    }

    private static MemberRow mapMember(ResultSet rs, int rowNum) throws SQLException {
        return new MemberRow(
                rs.getObject("user_id", UUID.class),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("role"),
                instant(rs.getObject("joined_at", OffsetDateTime.class)));
    }

    private static InviteRow mapInvite(ResultSet rs, int rowNum) throws SQLException {
        return new InviteRow(
                rs.getObject("id", UUID.class),
                rs.getObject("group_id", UUID.class),
                rs.getBytes("token_hash"),
                rs.getString("token_prefix"),
                rs.getObject("created_by", UUID.class),
                instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("expires_at", OffsetDateTime.class)),
                rs.getInt("max_uses"),
                rs.getInt("uses"),
                instant(rs.getObject("revoked_at", OffsetDateTime.class)));
    }
}
