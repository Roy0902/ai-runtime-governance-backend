package local.governance.integration;

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
public class HookCredentialRepository {

    public record Row(
            UUID id,
            UUID accountId,
            UUID scopeId,
            String integration,
            byte[] tokenHash,
            String tokenPrefix,
            Instant createdAt,
            Instant revokedAt) {}

    private final JdbcClient jdbc;

    public HookCredentialRepository(JdbcClient jdbc) {
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
                INSERT INTO hook_credential (
                    id, account_id, scope_id, integration, token_hash, token_prefix, created_at)
                VALUES (:id, :account, :scope, :integration, :tokenHash, :prefix, :createdAt)
                """)
                .param("id", row.id())
                .param("account", row.accountId())
                .param("scope", row.scopeId())
                .param("integration", row.integration())
                .param("tokenHash", row.tokenHash())
                .param("prefix", row.tokenPrefix())
                .param("createdAt", ts(row.createdAt()))
                .update();
    }

    public Optional<Row> activeForAccount(UUID accountId, String integration) {
        return jdbc.sql("""
                SELECT * FROM hook_credential
                WHERE account_id = :account AND integration = :integration AND revoked_at IS NULL
                """)
                .param("account", accountId)
                .param("integration", integration)
                .query(HookCredentialRepository::map)
                .optional();
    }

    public List<Row> forAccount(UUID accountId) {
        return jdbc.sql("""
                SELECT * FROM hook_credential
                WHERE account_id = :account
                ORDER BY created_at DESC, id DESC
                """)
                .param("account", accountId)
                .query(HookCredentialRepository::map)
                .list();
    }

    public Optional<Row> activeByHash(String integration, byte[] tokenHash) {
        return jdbc.sql("""
                SELECT * FROM hook_credential
                WHERE integration = :integration AND token_hash = :tokenHash AND revoked_at IS NULL
                """)
                .param("integration", integration)
                .param("tokenHash", tokenHash)
                .query(HookCredentialRepository::map)
                .optional();
    }

    public int revoke(UUID accountId, UUID credentialId, Instant now) {
        return jdbc.sql("""
                UPDATE hook_credential SET revoked_at = :now
                WHERE account_id = :account AND id = :id AND revoked_at IS NULL
                """)
                .param("now", ts(now))
                .param("account", accountId)
                .param("id", credentialId)
                .update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM hook_credential").update();
    }

    private static Row map(ResultSet rs, int rowNum) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getObject("scope_id", UUID.class),
                rs.getString("integration"),
                rs.getBytes("token_hash"),
                rs.getString("token_prefix"),
                instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("revoked_at", OffsetDateTime.class)));
    }
}
