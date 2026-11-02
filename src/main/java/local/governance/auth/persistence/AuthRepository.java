package local.governance.auth.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AuthRepository {

    public record AccountRow(
            UUID id,
            String email,
            String passwordHash,
            Instant emailVerifiedAt,
            String status,
            String mfaState,
            UUID scopeId,
            String gatewayEntitlement,
            Instant createdAt,
            Instant updatedAt,
            Instant disabledAt,
            Instant recoveryRequiredAt,
            long version) {}

    public record SessionRow(
            UUID id,
            byte[] tokenHash,
            String context,
            UUID accountId,
            String purpose,
            String csrfToken,
            Instant createdAt,
            Instant lastActivityAt,
            Instant absoluteExpiresAt,
            Instant idleExpiresAt,
            Instant recentAuthAt,
            Instant recentAuthExpiresAt,
            String state) {}

    public record LinkRow(
            UUID id,
            UUID accountId,
            String purpose,
            byte[] tokenHash,
            Instant createdAt,
            Instant expiresAt,
            Instant consumedAt,
            Instant supersededAt) {}

    public record MailRow(
            UUID id,
            UUID accountId,
            String address,
            String purpose,
            UUID linkId,
            String state,
            int attempts,
            Instant nextAttemptAt,
            byte[] tokenNonce,
            byte[] tokenCiphertext,
            String tokenKeyId,
            String lastErrorClass) {}

    public record OperationRow(
            UUID id,
            UUID accountId,
            String purpose,
            String state,
            UUID candidateSeedId,
            UUID recoverySetId,
            Instant createdAt,
            Instant expiresAt,
            Instant verifiedAt,
            Instant completedAt,
            Instant cancelledAt,
            Instant supersededAt,
            long version) {}

    public record SeedRow(
            UUID id,
            UUID accountId,
            String state,
            String keyId,
            byte[] nonce,
            byte[] ciphertext,
            int representationVersion,
            Instant createdAt,
            Instant activatedAt) {}

    public record RecoverySetRow(
            UUID id, UUID accountId, String state, Instant createdAt, Instant expiresAt, Instant activatedAt, Instant revokedAt) {}

    private final JdbcClient jdbc;

    public AuthRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    // --- account ---

    public void insertAccount(UUID id, String email, String passwordHash, Instant now) {
        jdbc.sql("""
                INSERT INTO account (id, email, password_hash, status, mfa_state, created_at, updated_at)
                VALUES (:id, :email, :hash, 'PENDING', 'NONE', :now, :now)
                """)
                .param("id", id)
                .param("email", email)
                .param("hash", passwordHash)
                .param("now", ts(now))
                .update();
    }

    public Optional<AccountRow> accountByEmail(String email) {
        return jdbc.sql("SELECT * FROM account WHERE email = :email")
                .param("email", email)
                .query(AuthRepository::mapAccount)
                .optional();
    }

    public Optional<AccountRow> accountById(UUID id) {
        return jdbc.sql("SELECT * FROM account WHERE id = :id")
                .param("id", id)
                .query(AuthRepository::mapAccount)
                .optional();
    }

    public Optional<AccountRow> accountByIdForUpdate(UUID id) {
        return jdbc.sql("SELECT * FROM account WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(AuthRepository::mapAccount)
                .optional();
    }

    public void markVerified(UUID accountId, Instant now) {
        jdbc.sql("""
                UPDATE account SET email_verified_at = :now, status = 'VERIFIED', updated_at = :now, version = version + 1
                WHERE id = :id
                """).param("now", ts(now)).param("id", accountId).update();
    }

    public void updatePassword(UUID accountId, String hash, Instant now) {
        jdbc.sql("""
                UPDATE account SET password_hash = :hash, updated_at = :now, version = version + 1
                WHERE id = :id
                """).param("hash", hash).param("now", ts(now)).param("id", accountId).update();
    }

    public int updatePasswordIfVersion(UUID accountId, String hash, long version, Instant now) {
        return jdbc.sql("""
                UPDATE account SET password_hash = :hash, updated_at = :now, version = version + 1
                WHERE id = :id AND version = :version
                """).param("hash", hash).param("now", ts(now)).param("id", accountId).param("version", version).update();
    }

    public void setMfaState(UUID accountId, String state, Instant now) {
        jdbc.sql("UPDATE account SET mfa_state = :state, updated_at = :now, version = version + 1 WHERE id = :id")
                .param("state", state).param("now", ts(now)).param("id", accountId).update();
    }

    public void setStatus(UUID accountId, String status, Instant disabledAt, Instant now) {
        jdbc.sql("""
                UPDATE account SET status = :status, disabled_at = :disabledAt, updated_at = :now, version = version + 1
                WHERE id = :id
                """).param("status", status).param("disabledAt", ts(disabledAt)).param("now", ts(now))
                .param("id", accountId).update();
    }

    public void setRecoveryRequired(UUID accountId, Instant now) {
        jdbc.sql("""
                UPDATE account SET mfa_state = 'RECOVERY_REQUIRED', recovery_required_at = :now,
                    updated_at = :now, version = version + 1
                WHERE id = :id
                """).param("now", ts(now)).param("id", accountId).update();
    }

    public void setScope(UUID accountId, UUID scopeId, Instant now) {
        jdbc.sql("UPDATE account SET scope_id = :scope, updated_at = :now, version = version + 1 WHERE id = :id AND scope_id IS NULL")
                .param("scope", scopeId).param("now", ts(now)).param("id", accountId).update();
    }

    public void setGateway(UUID accountId, String entitlement, Instant now) {
        jdbc.sql("UPDATE account SET gateway_entitlement = :entitlement, updated_at = :now, version = version + 1 WHERE id = :id")
                .param("entitlement", entitlement).param("now", ts(now)).param("id", accountId).update();
    }

    // --- session ---

    public void insertSession(SessionRow row) {
        jdbc.sql("""
                INSERT INTO web_session (id, token_hash, context, account_id, purpose, csrf_token, created_at,
                    last_activity_at, absolute_expires_at, idle_expires_at, recent_auth_at, recent_auth_expires_at, state)
                VALUES (:id, :token, :context, :account, :purpose, :csrf, :created, :activity, :absolute, :idle,
                    :recent, :recentExpires, 'ACTIVE')
                """)
                .param("id", row.id()).param("token", row.tokenHash()).param("context", row.context())
                .param("account", row.accountId()).param("purpose", row.purpose()).param("csrf", row.csrfToken())
                .param("created", ts(row.createdAt())).param("activity", ts(row.lastActivityAt()))
                .param("absolute", ts(row.absoluteExpiresAt())).param("idle", ts(row.idleExpiresAt()))
                .param("recent", ts(row.recentAuthAt())).param("recentExpires", ts(row.recentAuthExpiresAt()))
                .update();
    }

    public Optional<SessionRow> sessionByToken(byte[] tokenHash) {
        return jdbc.sql("SELECT * FROM web_session WHERE token_hash = :token")
                .param("token", tokenHash)
                .query(AuthRepository::mapSession)
                .optional();
    }

    public void touchSession(UUID id, Instant activity, Instant idleExpiresAt) {
        jdbc.sql("UPDATE web_session SET last_activity_at = :activity, idle_expires_at = :idle WHERE id = :id")
                .param("activity", ts(activity)).param("idle", ts(idleExpiresAt)).param("id", id).update();
    }

    public void endSession(UUID id, String state, Instant now) {
        jdbc.sql("UPDATE web_session SET state = :state, ended_at = :now WHERE id = :id AND state = 'ACTIVE'")
                .param("state", state).param("now", ts(now)).param("id", id).update();
    }

    public void revokeAllSessions(UUID accountId, Instant now) {
        jdbc.sql("UPDATE web_session SET state = 'REVOKED', ended_at = :now WHERE account_id = :account AND state = 'ACTIVE'")
                .param("now", ts(now)).param("account", accountId).update();
    }

    public void revokeOtherSessions(UUID accountId, UUID current, Instant now) {
        jdbc.sql("""
                UPDATE web_session SET state = 'REVOKED', ended_at = :now
                WHERE account_id = :account AND state = 'ACTIVE' AND id <> :current
                """).param("now", ts(now)).param("account", accountId).param("current", current).update();
    }

    public void setRecentAuth(UUID sessionId, Instant at, Instant expires) {
        jdbc.sql("UPDATE web_session SET recent_auth_at = :at, recent_auth_expires_at = :expires WHERE id = :id")
                .param("at", ts(at)).param("expires", ts(expires)).param("id", sessionId).update();
    }

    // --- links ---

    public void insertLink(LinkRow row) {
        jdbc.sql("""
                INSERT INTO confirmation_link (id, account_id, purpose, token_hash, created_at, expires_at)
                VALUES (:id, :account, :purpose, :token, :created, :expires)
                """)
                .param("id", row.id()).param("account", row.accountId()).param("purpose", row.purpose())
                .param("token", row.tokenHash()).param("created", ts(row.createdAt())).param("expires", ts(row.expiresAt()))
                .update();
    }

    public Optional<LinkRow> currentLink(UUID accountId, String purpose) {
        return jdbc.sql("""
                SELECT * FROM confirmation_link
                WHERE account_id = :account AND purpose = :purpose AND consumed_at IS NULL AND superseded_at IS NULL
                """).param("account", accountId).param("purpose", purpose).query(AuthRepository::mapLink).optional();
    }

    public Optional<LinkRow> linkByToken(byte[] tokenHash) {
        return jdbc.sql("SELECT * FROM confirmation_link WHERE token_hash = :token")
                .param("token", tokenHash).query(AuthRepository::mapLink).optional();
    }

    public Optional<LinkRow> lockLinkByToken(byte[] tokenHash) {
        return jdbc.sql("SELECT * FROM confirmation_link WHERE token_hash = :token FOR UPDATE")
                .param("token", tokenHash).query(AuthRepository::mapLink).optional();
    }

    public void supersedeCurrentLinks(UUID accountId, String purpose, Instant now) {
        jdbc.sql("""
                UPDATE confirmation_link SET superseded_at = :now
                WHERE account_id = :account AND purpose = :purpose AND consumed_at IS NULL AND superseded_at IS NULL
                """).param("now", ts(now)).param("account", accountId).param("purpose", purpose).update();
    }

    public boolean consumeLink(UUID linkId, Instant now) {
        return jdbc.sql("""
                UPDATE confirmation_link SET consumed_at = :now
                WHERE id = :id AND consumed_at IS NULL AND superseded_at IS NULL AND expires_at > :now
                """).param("now", ts(now)).param("id", linkId).update() == 1;
    }

    // --- mail ---

    public void insertMail(MailRow row) {
        jdbc.sql("""
                INSERT INTO mail_request (id, account_id, address, purpose, link_id, state, attempts, next_attempt_at,
                    token_nonce, token_ciphertext, token_key_id, created_at, updated_at)
                VALUES (:id, :account, :address, :purpose, :link, :state, 0, :next, :nonce, :token, :key, :now, :now)
                """)
                .param("id", row.id()).param("account", row.accountId()).param("address", row.address())
                .param("purpose", row.purpose()).param("link", row.linkId()).param("state", row.state())
                .param("next", ts(row.nextAttemptAt())).param("nonce", row.tokenNonce())
                .param("token", row.tokenCiphertext()).param("key", row.tokenKeyId())
                .param("now", ts(row.nextAttemptAt())).update();
    }

    public long uncompletedMailCount() {
        return jdbc.sql("""
                SELECT count(*) FROM mail_request
                WHERE state IN ('PENDING', 'DELAYED', 'SENDING', 'INELIGIBLE_ACCEPTED')
                """).query(Long.class).single();
    }

    public List<MailRow> dueMail(int limit, Instant now) {
        return jdbc.sql("""
                SELECT * FROM mail_request
                WHERE state IN ('PENDING', 'DELAYED') AND next_attempt_at <= :now
                ORDER BY next_attempt_at LIMIT :limit
                """).param("limit", limit).param("now", ts(now)).query(AuthRepository::mapMail).list();
    }

    public Optional<MailRow> lockMail(UUID id) {
        return jdbc.sql("SELECT * FROM mail_request WHERE id = :id FOR UPDATE")
                .param("id", id).query(AuthRepository::mapMail).optional();
    }

    public Optional<LinkRow> linkById(UUID id) {
        return jdbc.sql("SELECT * FROM confirmation_link WHERE id = :id")
                .param("id", id).query(AuthRepository::mapLink).optional();
    }

    public void clearMailToken(UUID id) {
        jdbc.sql("UPDATE mail_request SET token_nonce = NULL, token_ciphertext = NULL, token_key_id = NULL WHERE id = :id")
                .param("id", id).update();
    }

    public void updateMail(UUID id, String state, int attempts, Instant nextAttemptAt, String errorClass, Instant now) {
        jdbc.sql("""
                UPDATE mail_request SET state = :state, attempts = :attempts, next_attempt_at = :next,
                    last_error_class = :error, updated_at = :now
                WHERE id = :id
                """).param("state", state).param("attempts", attempts).param("next", ts(nextAttemptAt))
                .param("error", errorClass).param("now", ts(now)).param("id", id).update();
    }

    public void supersedeMailForLink(UUID linkId, Instant now) {
        jdbc.sql("UPDATE mail_request SET state = 'SUPERSEDED', updated_at = :now WHERE link_id = :link AND state IN ('PENDING','DELAYED')")
                .param("now", ts(now)).param("link", linkId).update();
    }

    public Optional<MailRow> latestMailForAddress(String address) {
        return jdbc.sql("SELECT * FROM mail_request WHERE address = :address ORDER BY created_at DESC LIMIT 1")
                .param("address", address).query(AuthRepository::mapMail).optional();
    }

    public boolean hasActiveHumanSession(UUID accountId) {
        return jdbc.sql("""
                SELECT count(*) FROM web_session
                WHERE account_id = :account AND context = 'HUMAN' AND state = 'ACTIVE'
                """).param("account", accountId).query(Long.class).single() > 0;
    }

    // --- operations ---

    public void insertOperation(OperationRow row) {
        jdbc.sql("""
                INSERT INTO enrollment_operation (id, account_id, purpose, state, candidate_seed_id, recovery_set_id,
                    created_at, expires_at, version)
                VALUES (:id, :account, :purpose, 'PENDING', :seed, :set, :created, :expires, 0)
                """)
                .param("id", row.id()).param("account", row.accountId()).param("purpose", row.purpose())
                .param("seed", row.candidateSeedId()).param("set", row.recoverySetId())
                .param("created", ts(row.createdAt())).param("expires", ts(row.expiresAt())).update();
    }

    public Optional<OperationRow> operationById(UUID id) {
        return jdbc.sql("SELECT * FROM enrollment_operation WHERE id = :id")
                .param("id", id).query(AuthRepository::mapOperation).optional();
    }

    public Optional<OperationRow> lockOperation(UUID id) {
        return jdbc.sql("SELECT * FROM enrollment_operation WHERE id = :id FOR UPDATE")
                .param("id", id).query(AuthRepository::mapOperation).optional();
    }

    public Optional<OperationRow> pendingOperation(UUID accountId, String purpose) {
        return jdbc.sql("""
                SELECT * FROM enrollment_operation
                WHERE account_id = :account AND purpose = :purpose AND state = 'PENDING'
                """).param("account", accountId).param("purpose", purpose).query(AuthRepository::mapOperation).optional();
    }

    public void supersedePendingOperations(UUID accountId, String purpose, Instant now) {
        jdbc.sql("""
                UPDATE enrollment_operation SET state = 'SUPERSEDED', superseded_at = :now, version = version + 1
                WHERE account_id = :account AND purpose = :purpose AND state = 'PENDING'
                """).param("now", ts(now)).param("account", accountId).param("purpose", purpose).update();
    }

    public void completeOperation(UUID id, Instant now) {
        jdbc.sql("""
                UPDATE enrollment_operation SET state = 'COMPLETED', completed_at = :now, version = version + 1
                WHERE id = :id AND state = 'PENDING'
                """).param("now", ts(now)).param("id", id).update();
    }

    public void cancelOperation(UUID id, Instant now) {
        jdbc.sql("""
                UPDATE enrollment_operation SET state = 'CANCELLED', cancelled_at = :now, version = version + 1
                WHERE id = :id AND state = 'PENDING'
                """).param("now", ts(now)).param("id", id).update();
    }

    public void cancelAllPendingOperations(UUID accountId, Instant now) {
        jdbc.sql("""
                UPDATE enrollment_operation SET state = 'CANCELLED', cancelled_at = :now, version = version + 1
                WHERE account_id = :account AND state = 'PENDING'
                """).param("now", ts(now)).param("account", accountId).update();
    }

    public void retireAllActiveSeeds(UUID accountId, Instant now) {
        jdbc.sql("UPDATE totp_seed SET state = 'RETIRED', retired_at = :now WHERE account_id = :account AND state = 'ACTIVE'")
                .param("now", ts(now)).param("account", accountId).update();
    }

    public void revokeAllActiveRecoverySets(UUID accountId, Instant now) {
        jdbc.sql("UPDATE recovery_code_set SET state = 'REVOKED', revoked_at = :now WHERE account_id = :account AND state = 'ACTIVE'")
                .param("now", ts(now)).param("account", accountId).update();
    }

    // --- seeds and recovery sets ---

    public void insertSeed(SeedRow row) {
        jdbc.sql("""
                INSERT INTO totp_seed (id, account_id, state, key_id, nonce, ciphertext, representation_version, created_at)
                VALUES (:id, :account, :state, :key, :nonce, :ciphertext, :version, :created)
                """)
                .param("id", row.id()).param("account", row.accountId()).param("state", row.state())
                .param("key", row.keyId()).param("nonce", row.nonce()).param("ciphertext", row.ciphertext())
                .param("version", row.representationVersion()).param("created", ts(row.createdAt())).update();
    }

    public Optional<SeedRow> activeSeed(UUID accountId) {
        return jdbc.sql("SELECT * FROM totp_seed WHERE account_id = :account AND state = 'ACTIVE'")
                .param("account", accountId).query(AuthRepository::mapSeed).optional();
    }

    public Optional<SeedRow> seedById(UUID id) {
        return jdbc.sql("SELECT * FROM totp_seed WHERE id = :id")
                .param("id", id).query(AuthRepository::mapSeed).optional();
    }

    public void activateSeed(UUID id, Instant now) {
        jdbc.sql("UPDATE totp_seed SET state = 'ACTIVE', activated_at = :now WHERE id = :id AND state = 'PENDING'")
                .param("now", ts(now)).param("id", id).update();
    }

    public void retireSeed(UUID id, Instant now) {
        jdbc.sql("UPDATE totp_seed SET state = 'RETIRED', retired_at = :now WHERE id = :id AND state = 'ACTIVE'")
                .param("now", ts(now)).param("id", id).update();
    }

    public void insertRecoverySet(RecoverySetRow row) {
        jdbc.sql("""
                INSERT INTO recovery_code_set (id, account_id, state, created_at, expires_at)
                VALUES (:id, :account, :state, :created, :expires)
                """).param("id", row.id()).param("account", row.accountId()).param("state", row.state())
                .param("created", ts(row.createdAt())).param("expires", ts(row.expiresAt())).update();
    }

    public Optional<RecoverySetRow> activeRecoverySet(UUID accountId) {
        return jdbc.sql("SELECT * FROM recovery_code_set WHERE account_id = :account AND state = 'ACTIVE'")
                .param("account", accountId).query(AuthRepository::mapRecoverySet).optional();
    }

    public Optional<RecoverySetRow> recoverySetById(UUID id) {
        return jdbc.sql("SELECT * FROM recovery_code_set WHERE id = :id")
                .param("id", id).query(AuthRepository::mapRecoverySet).optional();
    }

    public void activateRecoverySet(UUID id, Instant now) {
        jdbc.sql("UPDATE recovery_code_set SET state = 'ACTIVE', activated_at = :now WHERE id = :id AND state = 'PENDING'")
                .param("now", ts(now)).param("id", id).update();
    }

    public void revokeRecoverySet(UUID id, Instant now) {
        jdbc.sql("UPDATE recovery_code_set SET state = 'REVOKED', revoked_at = :now WHERE id = :id AND state = 'ACTIVE'")
                .param("now", ts(now)).param("id", id).update();
    }

    public void insertRecoveryCode(UUID id, UUID setId, byte[] verifier) {
        jdbc.sql("INSERT INTO recovery_code (id, set_id, verifier) VALUES (:id, :set, :verifier)")
                .param("id", id).param("set", setId).param("verifier", verifier).update();
    }

    public record RecoveryCodeRow(UUID id, UUID setId, byte[] verifier, Instant consumedAt) {}

    public List<RecoveryCodeRow> recoveryCodes(UUID setId) {
        return jdbc.sql("SELECT * FROM recovery_code WHERE set_id = :set")
                .param("set", setId)
                .query((rs, rowNum) -> new RecoveryCodeRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("set_id", UUID.class),
                        rs.getBytes("verifier"),
                        instant(rs.getObject("consumed_at", OffsetDateTime.class))))
                .list();
    }

    public boolean consumeRecoveryCode(UUID id, Instant now) {
        return jdbc.sql("UPDATE recovery_code SET consumed_at = :now WHERE id = :id AND consumed_at IS NULL")
                .param("now", ts(now)).param("id", id).update() == 1;
    }

    // --- totp replay ---

    public boolean recordTotpStep(UUID accountId, long step, Instant now) {
        return jdbc.sql("""
                INSERT INTO totp_used_step (account_id, time_step, accepted_at)
                VALUES (:account, :step, :now) ON CONFLICT DO NOTHING
                """).param("account", accountId).param("step", step).param("now", ts(now)).update() == 1;
    }

    // --- rate and failures ---

    public void recordRateEvent(String type, String key, Instant now) {
        jdbc.sql("INSERT INTO rate_event (bucket_type, bucket_key, occurred_at) VALUES (:type, :key, :now)")
                .param("type", type).param("key", key).param("now", ts(now)).update();
        jdbc.sql("""
                INSERT INTO rate_bucket_state (bucket_type, bucket_key, last_seen_at) VALUES (:type, :key, :now)
                ON CONFLICT (bucket_type, bucket_key) DO UPDATE SET last_seen_at = :now
                """).param("type", type).param("key", key).param("now", ts(now)).update();
    }

    public long countRateEvents(String type, String key, Instant since, Instant now) {
        return jdbc.sql("""
                SELECT count(*) FROM rate_event
                WHERE bucket_type = :type AND bucket_key = :key AND occurred_at > :since AND occurred_at <= :now
                """).param("type", type).param("key", key).param("since", ts(since)).param("now", ts(now))
                .query(Long.class).single();
    }

    public long countBuckets(String type) {
        return jdbc.sql("SELECT count(*) FROM rate_bucket_state WHERE bucket_type = :type")
                .param("type", type).query(Long.class).single();
    }

    public void recordFailure(String identity, Instant now) {
        jdbc.sql("INSERT INTO failed_auth_event (identity, occurred_at) VALUES (:identity, :now)")
                .param("identity", identity).param("now", ts(now)).update();
        jdbc.sql("""
                INSERT INTO failed_auth_state (identity, last_seen_at) VALUES (:identity, :now)
                ON CONFLICT (identity) DO UPDATE SET last_seen_at = :now
                """).param("identity", identity).param("now", ts(now)).update();
    }

    public long countFailures(String identity, Instant since, Instant now) {
        return jdbc.sql("""
                SELECT count(*) FROM failed_auth_event
                WHERE identity = :identity AND occurred_at > :since AND occurred_at <= :now
                """).param("identity", identity).param("since", ts(since)).param("now", ts(now))
                .query(Long.class).single();
    }

    public Optional<Instant> cooldown(String identity) {
        return jdbc.sql("SELECT cooldown_until FROM failed_auth_state WHERE identity = :identity")
                .param("identity", identity)
                .query((rs, rowNum) -> instant(rs.getObject("cooldown_until", OffsetDateTime.class)))
                .optional();
    }

    public void setCooldown(String identity, Instant until, Instant now) {
        jdbc.sql("""
                INSERT INTO failed_auth_state (identity, cooldown_until, last_seen_at) VALUES (:identity, :until, :now)
                ON CONFLICT (identity) DO UPDATE SET cooldown_until = :until, last_seen_at = :now
                """).param("identity", identity).param("until", ts(until)).param("now", ts(now)).update();
    }

    public void clearFailures(String identity) {
        jdbc.sql("DELETE FROM failed_auth_event WHERE identity = :identity").param("identity", identity).update();
        jdbc.sql("DELETE FROM failed_auth_state WHERE identity = :identity").param("identity", identity).update();
    }

    // --- audit ---

    public void insertAudit(UUID id, Instant now, String operator, String action, UUID accountId, String reason,
            String outcome, String changedFieldsJson, UUID correlationId) {
        jdbc.sql("""
                INSERT INTO host_audit (id, occurred_at, operator_id, action, target_account_id, reason, outcome,
                    changed_fields, correlation_id)
                VALUES (:id, :now, :operator, :action, :account, :reason, :outcome, CAST(:fields AS jsonb), :correlation)
                """).param("id", id).param("now", ts(now)).param("operator", operator).param("action", action)
                .param("account", accountId).param("reason", reason).param("outcome", outcome)
                .param("fields", changedFieldsJson).param("correlation", correlationId).update();
    }

    public long auditCount(UUID accountId) {
        return jdbc.sql("SELECT count(*) FROM host_audit WHERE target_account_id = :account")
                .param("account", accountId).query(Long.class).single();
    }

    // --- test-support operations (never exposed in production) ---

    public long deleteNeverVerifiedBefore(Instant cutoff) {
        return jdbc.sql("""
                DELETE FROM account a
                WHERE a.email_verified_at IS NULL
                  AND :cutoff >= GREATEST(a.created_at,
                      COALESCE((SELECT max(l.created_at) FROM confirmation_link l
                                WHERE l.account_id = a.id AND l.purpose = 'VERIFY'), a.created_at))
                """).param("cutoff", ts(cutoff)).update();
    }

    public void fillBuckets(String type, int count, Instant now) {
        jdbc.sql("""
                INSERT INTO rate_bucket_state (bucket_type, bucket_key, last_seen_at)
                SELECT :type, 'test-' || g, :now FROM generate_series(1, :count) g
                ON CONFLICT DO NOTHING
                """).param("type", type).param("count", count).param("now", ts(now)).update();
    }

    public void tamperActiveSeed(UUID accountId) {
        byte[] noise = new byte[32];
        new java.security.SecureRandom().nextBytes(noise);
        jdbc.sql("UPDATE totp_seed SET ciphertext = :noise WHERE account_id = :account AND state = 'ACTIVE'")
                .param("noise", noise).param("account", accountId).update();
    }

    public void resetTestState() {
        jdbc.sql("DELETE FROM rate_event").update();
        jdbc.sql("DELETE FROM rate_bucket_state").update();
        jdbc.sql("DELETE FROM failed_auth_event").update();
        jdbc.sql("DELETE FROM failed_auth_state").update();
    }

    // --- mappers ---

    private static AccountRow mapAccount(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AccountRow(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("password_hash"),
                instant(rs.getObject("email_verified_at", OffsetDateTime.class)),
                rs.getString("status"),
                rs.getString("mfa_state"),
                rs.getObject("scope_id", UUID.class),
                rs.getString("gateway_entitlement"),
                instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("updated_at", OffsetDateTime.class)),
                instant(rs.getObject("disabled_at", OffsetDateTime.class)),
                instant(rs.getObject("recovery_required_at", OffsetDateTime.class)),
                rs.getLong("version"));
    }

    private static SessionRow mapSession(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new SessionRow(
                rs.getObject("id", UUID.class),
                rs.getBytes("token_hash"),
                rs.getString("context"),
                rs.getObject("account_id", UUID.class),
                rs.getString("purpose"),
                rs.getString("csrf_token"),
                instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("last_activity_at", OffsetDateTime.class)),
                instant(rs.getObject("absolute_expires_at", OffsetDateTime.class)),
                instant(rs.getObject("idle_expires_at", OffsetDateTime.class)),
                instant(rs.getObject("recent_auth_at", OffsetDateTime.class)),
                instant(rs.getObject("recent_auth_expires_at", OffsetDateTime.class)),
                rs.getString("state"));
    }

    private static LinkRow mapLink(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new LinkRow(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getString("purpose"),
                rs.getBytes("token_hash"),
                instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("expires_at", OffsetDateTime.class)),
                instant(rs.getObject("consumed_at", OffsetDateTime.class)),
                instant(rs.getObject("superseded_at", OffsetDateTime.class)));
    }

    private static MailRow mapMail(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new MailRow(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getString("address"),
                rs.getString("purpose"),
                rs.getObject("link_id", UUID.class),
                rs.getString("state"),
                rs.getInt("attempts"),
                instant(rs.getObject("next_attempt_at", OffsetDateTime.class)),
                rs.getBytes("token_nonce"),
                rs.getBytes("token_ciphertext"),
                rs.getString("token_key_id"),
                rs.getString("last_error_class"));
    }

    private static OperationRow mapOperation(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new OperationRow(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getString("purpose"),
                rs.getString("state"),
                rs.getObject("candidate_seed_id", UUID.class),
                rs.getObject("recovery_set_id", UUID.class),
                instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("expires_at", OffsetDateTime.class)),
                instant(rs.getObject("verified_at", OffsetDateTime.class)),
                instant(rs.getObject("completed_at", OffsetDateTime.class)),
                instant(rs.getObject("cancelled_at", OffsetDateTime.class)),
                instant(rs.getObject("superseded_at", OffsetDateTime.class)),
                rs.getLong("version"));
    }

    private static SeedRow mapSeed(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new SeedRow(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getString("state"),
                rs.getString("key_id"),
                rs.getBytes("nonce"),
                rs.getBytes("ciphertext"),
                rs.getInt("representation_version"),
                instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("activated_at", OffsetDateTime.class)));
    }

    private static RecoverySetRow mapRecoverySet(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RecoverySetRow(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getString("state"),
                instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("expires_at", OffsetDateTime.class)),
                instant(rs.getObject("activated_at", OffsetDateTime.class)),
                instant(rs.getObject("revoked_at", OffsetDateTime.class)));
    }
}
