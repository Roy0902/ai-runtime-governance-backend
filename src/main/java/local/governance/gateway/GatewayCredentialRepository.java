package local.governance.gateway;

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
public class GatewayCredentialRepository {

    public record Row(
            UUID id,
            UUID accountId,
            UUID scopeId,
            String name,
            byte[] tokenHash,
            String tokenPrefix,
            Instant createdAt,
            Instant lastUsedAt,
            Instant revokedAt,
            UUID groupId) {}

    private final JdbcClient jdbc;

    public GatewayCredentialRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public void insert(Row row) {
        jdbc.sql("""
                INSERT INTO gateway_credential (
                    id, account_id, scope_id, name, token_hash, token_prefix, created_at, group_id)
                VALUES (:id, :account, :scope, :name, :tokenHash, :prefix, :createdAt, :groupId)
                """)
                .param("id", row.id())
                .param("account", row.accountId())
                .param("scope", row.scopeId())
                .param("name", row.name())
                .param("tokenHash", row.tokenHash())
                .param("prefix", row.tokenPrefix())
                .param("createdAt", ts(row.createdAt()))
                .param("groupId", row.groupId())
                .update();
    }

    public List<Row> forAccount(UUID accountId) {
        return jdbc.sql("""
                SELECT * FROM gateway_credential
                WHERE account_id = :account
                ORDER BY created_at DESC, id DESC
                """)
                .param("account", accountId)
                .query(GatewayCredentialRepository::map)
                .list();
    }

    public long activeCount(UUID accountId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM gateway_credential
                WHERE account_id = :account AND revoked_at IS NULL
                """)
                .param("account", accountId)
                .query(Long.class)
                .single();
    }

    public Optional<Row> activeByName(UUID accountId, String name) {
        return jdbc.sql("""
                SELECT * FROM gateway_credential
                WHERE account_id = :account AND lower(name) = lower(:name) AND revoked_at IS NULL
                """)
                .param("account", accountId)
                .param("name", name)
                .query(GatewayCredentialRepository::map)
                .optional();
    }

    public Optional<Row> activeByHash(byte[] tokenHash) {
        return jdbc.sql("""
                SELECT * FROM gateway_credential
                WHERE token_hash = :tokenHash AND revoked_at IS NULL
                """)
                .param("tokenHash", tokenHash)
                .query(GatewayCredentialRepository::map)
                .optional();
    }

    public int markUsed(UUID credentialId, Instant now) {
        return jdbc.sql("""
                UPDATE gateway_credential SET last_used_at = :now
                WHERE id = :id AND revoked_at IS NULL
                """)
                .param("now", ts(now))
                .param("id", credentialId)
                .update();
    }

    public int revoke(UUID accountId, UUID credentialId, Instant now) {
        return jdbc.sql("""
                UPDATE gateway_credential SET revoked_at = :now
                WHERE account_id = :account AND id = :id AND revoked_at IS NULL
                """)
                .param("now", ts(now))
                .param("account", accountId)
                .param("id", credentialId)
                .update();
    }

    public int revokeForGroupMember(UUID groupId, UUID accountId, Instant now) {
        return jdbc.sql("""
                UPDATE gateway_credential SET revoked_at = :now
                WHERE group_id = :group AND account_id = :account AND revoked_at IS NULL
                """)
                .param("group", groupId)
                .param("account", accountId)
                .param("now", ts(now))
                .update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM gateway_credential").update();
    }

    private static Row map(ResultSet rs, int rowNum) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getObject("scope_id", UUID.class),
                rs.getString("name"),
                rs.getBytes("token_hash"),
                rs.getString("token_prefix"),
                instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("last_used_at", OffsetDateTime.class)),
                instant(rs.getObject("revoked_at", OffsetDateTime.class)),
                rs.getObject("group_id", UUID.class));
    }
}
