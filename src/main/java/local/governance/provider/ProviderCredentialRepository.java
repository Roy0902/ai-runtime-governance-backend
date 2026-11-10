package local.governance.provider;

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
public class ProviderCredentialRepository {

    public record Row(
            UUID id,
            UUID accountId,
            String provider,
            String name,
            String accessKey,
            String secretHint,
            byte[] nonce,
            byte[] ciphertext,
            byte[] wrappedKey,
            String keyId,
            Instant createdAt,
            Instant updatedAt,
            long version,
            UUID groupId) {}

    private final JdbcClient jdbc;

    public ProviderCredentialRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public void upsert(Row row) {
        String conflict = row.groupId() == null
                ? "ON CONFLICT (account_id, provider) DO UPDATE SET"
                : "ON CONFLICT (group_id, provider) WHERE group_id IS NOT NULL DO UPDATE SET";
        jdbc.sql("""
                INSERT INTO provider_credential (
                    id, account_id, group_id, provider, name, access_key, secret_hint,
                    secret_nonce, secret_ciphertext, wrapped_key, key_id,
                    created_at, updated_at, version)
                VALUES (
                    :id, :account, :groupId, :provider, :name, :accessKey, :secretHint,
                    :nonce, :ciphertext, :wrappedKey, :keyId,
                    :createdAt, :updatedAt, 0)
                """ + " " + conflict + """
                    name = EXCLUDED.name,
                    access_key = EXCLUDED.access_key,
                    secret_hint = EXCLUDED.secret_hint,
                    secret_nonce = EXCLUDED.secret_nonce,
                    secret_ciphertext = EXCLUDED.secret_ciphertext,
                    wrapped_key = EXCLUDED.wrapped_key,
                    key_id = EXCLUDED.key_id,
                    updated_at = EXCLUDED.updated_at,
                    version = provider_credential.version + 1
                """)
                .param("id", row.id())
                .param("account", row.accountId())
                .param("groupId", row.groupId())
                .param("provider", row.provider())
                .param("name", row.name())
                .param("accessKey", row.accessKey())
                .param("secretHint", row.secretHint())
                .param("nonce", row.nonce())
                .param("ciphertext", row.ciphertext())
                .param("wrappedKey", row.wrappedKey())
                .param("keyId", row.keyId())
                .param("createdAt", ts(row.createdAt()))
                .param("updatedAt", ts(row.updatedAt()))
                .update();
    }

    public List<Row> forAccount(UUID accountId) {
        return jdbc.sql("""
                SELECT * FROM provider_credential
                WHERE account_id = :account
                ORDER BY provider
                """)
                .param("account", accountId)
                .query(ProviderCredentialRepository::map)
                .list();
    }

    public Optional<Row> find(UUID accountId, String provider) {
        return jdbc.sql("""
                SELECT * FROM provider_credential
                WHERE account_id = :account AND provider = :provider
                """)
                .param("account", accountId)
                .param("provider", provider)
                .query(ProviderCredentialRepository::map)
                .optional();
    }

    public List<Row> forGroup(UUID groupId) {
        return jdbc.sql("""
                SELECT * FROM provider_credential
                WHERE group_id = :group
                ORDER BY provider
                """)
                .param("group", groupId)
                .query(ProviderCredentialRepository::map)
                .list();
    }

    public Optional<Row> findForGroup(UUID groupId, String provider) {
        return jdbc.sql("""
                SELECT * FROM provider_credential
                WHERE group_id = :group AND provider = :provider
                """)
                .param("group", groupId)
                .param("provider", provider)
                .query(ProviderCredentialRepository::map)
                .optional();
    }

    public int deleteForGroup(UUID groupId, String provider) {
        return jdbc.sql("""
                DELETE FROM provider_credential
                WHERE group_id = :group AND provider = :provider
                """)
                .param("group", groupId)
                .param("provider", provider)
                .update();
    }

    public int delete(UUID accountId, String provider) {
        return jdbc.sql("""
                DELETE FROM provider_credential
                WHERE account_id = :account AND provider = :provider
                """)
                .param("account", accountId)
                .param("provider", provider)
                .update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM provider_credential").update();
    }

    private static Row map(ResultSet rs, int rowNum) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getString("provider"),
                rs.getString("name"),
                rs.getString("access_key"),
                rs.getString("secret_hint"),
                rs.getBytes("secret_nonce"),
                rs.getBytes("secret_ciphertext"),
                rs.getBytes("wrapped_key"),
                rs.getString("key_id"),
                instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("updated_at", OffsetDateTime.class)),
                rs.getLong("version"),
                rs.getObject("group_id", UUID.class));
    }
}
