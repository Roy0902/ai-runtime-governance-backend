package local.governance.gateway;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class GatewayConversationRepository {

    public record Row(
            String conversationKey,
            UUID scopeId,
            UUID traceId,
            String guardedMessageHash,
            String guardOutcome,
            boolean redacted,
            Instant createdAt,
            Instant expiresAt) {}

    private final JdbcClient jdbc;

    public GatewayConversationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public void upsert(Row row) {
        jdbc.sql("""
                INSERT INTO gateway_conversation (
                    conversation_key, scope_id, trace_id, guarded_message_hash,
                    guard_outcome, redacted, created_at, expires_at)
                VALUES (
                    :key, :scope, :trace, :messageHash, :outcome, :redacted, :createdAt, :expiresAt)
                ON CONFLICT (conversation_key) DO UPDATE
                SET trace_id = EXCLUDED.trace_id,
                    guarded_message_hash = EXCLUDED.guarded_message_hash,
                    guard_outcome = EXCLUDED.guard_outcome,
                    redacted = gateway_conversation.redacted OR EXCLUDED.redacted,
                    expires_at = EXCLUDED.expires_at
                """)
                .param("key", row.conversationKey())
                .param("scope", row.scopeId())
                .param("trace", row.traceId())
                .param("messageHash", row.guardedMessageHash())
                .param("outcome", row.guardOutcome())
                .param("redacted", row.redacted())
                .param("createdAt", ts(row.createdAt()))
                .param("expiresAt", ts(row.expiresAt()))
                .update();
    }

    public Optional<Row> find(String conversationKey, UUID scopeId) {
        return jdbc.sql("""
                SELECT * FROM gateway_conversation
                WHERE conversation_key = :key AND scope_id = :scope
                """)
                .param("key", conversationKey)
                .param("scope", scopeId)
                .query(GatewayConversationRepository::map)
                .optional();
    }

    public int delete(String conversationKey, UUID scopeId) {
        return jdbc.sql("""
                DELETE FROM gateway_conversation
                WHERE conversation_key = :key AND scope_id = :scope
                """)
                .param("key", conversationKey)
                .param("scope", scopeId)
                .update();
    }

    public int deleteExpired(UUID scopeId, Instant now) {
        return jdbc.sql("""
                DELETE FROM gateway_conversation
                WHERE scope_id = :scope AND expires_at <= :now
                """)
                .param("scope", scopeId)
                .param("now", ts(now))
                .update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM gateway_conversation").update();
    }

    private static Row map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Row(
                rs.getString("conversation_key"),
                rs.getObject("scope_id", UUID.class),
                rs.getObject("trace_id", UUID.class),
                rs.getString("guarded_message_hash"),
                rs.getString("guard_outcome"),
                rs.getBoolean("redacted"),
                instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("expires_at", OffsetDateTime.class)));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
