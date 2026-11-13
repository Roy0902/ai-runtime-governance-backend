package local.governance.admin;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Host-operator administration against the Keycloak-linked app_user table.
 * Authentication and MFA recovery belong to Keycloak, not this CLI.
 */
@Service
public class AppUserAdminService {

    public record AppUserRow(
            UUID id,
            String keycloakSub,
            String email,
            String status,
            String gatewayEntitlement) {}

    private static final String SELECT = """
            SELECT id, keycloak_sub, email, status, gateway_entitlement
            FROM app_user
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    public AppUserAdminService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Optional<AppUserRow> byEmail(String email) {
        return jdbc.sql(SELECT + " WHERE lower(email) = lower(:email)")
                .param("email", email)
                .query(AppUserAdminService::map)
                .optional();
    }

    public Optional<AppUserRow> byId(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(AppUserAdminService::map)
                .optional();
    }

    @Transactional
    public Map<String, Object> grant(AppUserRow user, String reason, String operator, UUID correlationId) {
        if (!"ACTIVE".equals(user.status()) || user.keycloakSub() == null || user.keycloakSub().isBlank()) {
            throw new AdminException(4, "STATE_CONFLICT", "Account is not linked to an active Keycloak identity.");
        }
        if ("GRANTED".equals(user.gatewayEntitlement())) {
            return audit(user, "GATEWAY_GRANT", reason, operator, "NO_CHANGE", "[]", correlationId, false);
        }
        Instant now = clock.instant();
        jdbc.sql("""
                UPDATE app_user
                SET gateway_entitlement = 'GRANTED', updated_at = :now, version = version + 1
                WHERE id = :id
                """)
                .param("now", timestamp(now))
                .param("id", user.id())
                .update();
        return audit(user, "GATEWAY_GRANT", reason, operator, "CHANGED", "[\"gatewayEntitlement\"]",
                correlationId, true);
    }

    @Transactional
    public Map<String, Object> revoke(AppUserRow user, String reason, String operator, UUID correlationId) {
        if ("REVOKED".equals(user.gatewayEntitlement())) {
            return audit(user, "GATEWAY_REVOKE", reason, operator, "NO_CHANGE", "[]", correlationId, false);
        }
        jdbc.sql("""
                UPDATE app_user
                SET gateway_entitlement = 'REVOKED', updated_at = :now, version = version + 1
                WHERE id = :id
                """)
                .param("now", timestamp(clock.instant()))
                .param("id", user.id())
                .update();
        return audit(user, "GATEWAY_REVOKE", reason, operator, "CHANGED", "[\"gatewayEntitlement\"]",
                correlationId, true);
    }

    @Transactional
    public Map<String, Object> disable(AppUserRow user, String reason, String operator, UUID correlationId) {
        if ("DISABLED".equals(user.status())) {
            return audit(user, "ACCOUNT_DISABLE", reason, operator, "NO_CHANGE", "[]", correlationId, false);
        }
        Instant now = clock.instant();
        jdbc.sql("""
                UPDATE app_user
                SET status = 'DISABLED',
                    disabled_at = :now,
                    gateway_entitlement = 'ABSENT',
                    updated_at = :now,
                    version = version + 1
                WHERE id = :id
                """)
                .param("now", timestamp(now))
                .param("id", user.id())
                .update();
        return audit(user, "ACCOUNT_DISABLE", reason, operator, "CHANGED",
                "[\"status\",\"gatewayEntitlement\"]", correlationId, true);
    }

    @Transactional
    public Map<String, Object> enable(AppUserRow user, String reason, String operator, UUID correlationId) {
        if (!"DISABLED".equals(user.status())) {
            return audit(user, "ACCOUNT_ENABLE", reason, operator, "NO_CHANGE", "[]", correlationId, false);
        }
        jdbc.sql("""
                UPDATE app_user
                SET status = 'ACTIVE', disabled_at = NULL, updated_at = :now, version = version + 1
                WHERE id = :id
                """)
                .param("now", timestamp(clock.instant()))
                .param("id", user.id())
                .update();
        return audit(user, "ACCOUNT_ENABLE", reason, operator, "CHANGED", "[\"status\"]", correlationId, true);
    }

    private Map<String, Object> audit(
            AppUserRow user,
            String action,
            String reason,
            String operator,
            String outcome,
            String changedFields,
            UUID correlationId,
            boolean changed) {
        Instant now = clock.instant();
        UUID auditId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO host_audit (
                    id, occurred_at, operator_id, action, target_user_id, reason,
                    outcome, changed_fields, correlation_id)
                VALUES (
                    :id, :now, :operator, :action, :target, :reason,
                    :outcome, CAST(:changed AS jsonb), :correlation)
                """)
                .param("id", auditId)
                .param("now", timestamp(now))
                .param("operator", operator)
                .param("action", action)
                .param("target", user.id())
                .param("reason", reason)
                .param("outcome", outcome)
                .param("changed", changedFields)
                .param("correlation", correlationId)
                .update();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("action", action);
        body.put("accountId", user.id().toString());
        body.put("changed", changed);
        body.put("auditId", auditId.toString());
        return body;
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static AppUserRow map(ResultSet rs, int rowNum) throws SQLException {
        return new AppUserRow(
                rs.getObject("id", UUID.class),
                rs.getString("keycloak_sub"),
                rs.getString("email"),
                rs.getString("status"),
                rs.getString("gateway_entitlement"));
    }
}
