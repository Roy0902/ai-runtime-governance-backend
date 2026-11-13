package local.governance.security;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import local.governance.api.APIException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application-side identity projection for Keycloak-authenticated humans.
 *
 * Keycloak is the source of truth for authentication. This service maps the
 * immutable OIDC subject to an app_user/app_scope row and owns application
 * profile, status and gateway entitlement.
 */
@Service
public class AppUserService {

    public record AppUser(
            UUID id,
            String keycloakSub,
            String email,
            String displayName,
            String status,
            String gatewayEntitlement,
            UUID scopeId,
            Instant createdAt,
            Instant updatedAt,
            Instant disabledAt,
            long version) {}

    public record NativeUserPrincipal(
            UUID id,
            String email,
            UUID scopeId,
            String csrfToken,
            Instant recentAuthExpiresAt) {}

    private static final String SELECT = """
            SELECT u.id,
                   u.keycloak_sub,
                   u.email,
                   u.display_name,
                   u.status,
                   u.gateway_entitlement,
                   s.id AS scope_id,
                   u.created_at,
                   u.updated_at,
                   u.disabled_at,
                   u.version
            FROM app_user u
            LEFT JOIN app_scope s ON s.owner_user_id = u.id
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    public AppUserService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public AppUser requireCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof NativeUserPrincipal nativePrincipal) {
            AppUser user = findById(nativePrincipal.id()).orElseThrow(APIException::signInRequired);
            if (!"ACTIVE".equals(user.status())) {
                throw new APIException("ACCOUNT_DISABLED", "This account is disabled.");
            }
            return user;
        }
        OidcUser oidc = currentOidc();
        AppUser user = ensureUser(oidc);
        if (!"ACTIVE".equals(user.status())) {
            throw new APIException("ACCOUNT_DISABLED", "This account is disabled.");
        }
        return user;
    }

    public UUID requireScopeId() {
        AppUser user = requireCurrentUser();
        if (user.scopeId() == null) {
            throw APIException.notFound();
        }
        return user.scopeId();
    }

    public void requireRecentAuth() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof NativeUserPrincipal nativePrincipal) {
            if (nativePrincipal.recentAuthExpiresAt() == null
                    || nativePrincipal.recentAuthExpiresAt().isBefore(clock.instant())) {
                throw new APIException("RECENT_AUTH_REQUIRED", "Confirm your identity to continue.");
            }
            return;
        }
        Instant authTime = authTime(currentOidc());
        if (authTime == null || authTime.plus(Duration.ofMinutes(10)).isBefore(clock.instant())) {
            throw new APIException("RECENT_AUTH_REQUIRED", "Confirm your identity to continue.");
        }
    }

    public List<String> currentRoles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof NativeUserPrincipal nativePrincipal) {
            AppUser user = findById(nativePrincipal.id()).orElse(null);
            if (user != null && "GRANTED".equals(user.gatewayEntitlement())) {
                return List.of("user", "gateway-user");
            }
            return List.of("user");
        }
        return roles(currentOidc());
    }

    public Instant currentAuthTime() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof NativeUserPrincipal) {
            return null;
        }
        return authTime(currentOidc());
    }

    public Optional<AppUser> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE u.id = :id")
                .param("id", id)
                .query(AppUserService::map)
                .optional();
    }

    public Optional<AppUser> findByKeycloakSub(String keycloakSub) {
        return jdbc.sql(SELECT + " WHERE u.keycloak_sub = :sub")
                .param("sub", keycloakSub)
                .query(AppUserService::map)
                .optional();
    }

    public AppUser requireActive(UUID id) {
        AppUser user = findById(id).orElseThrow(APIException::signInRequired);
        if (!"ACTIVE".equals(user.status())) {
            throw new APIException("ACCOUNT_DISABLED", "This account is disabled.");
        }
        return user;
    }

    public AppUser requireGatewayEntitled(UUID id) {
        AppUser user = requireActive(id);
        if (user.scopeId() == null) {
            throw new APIException("GATEWAY_NOT_ENTITLED", "This account has no private scope.");
        }
        if (!"GRANTED".equals(user.gatewayEntitlement())) {
            throw new APIException(
                    "GATEWAY_NOT_ENTITLED",
                    "This account is not permitted to use the model gateway.");
        }
        return user;
    }

    @Transactional
    public AppUser ensureUser(OidcUser oidc) {
        String sub = oidc.getSubject();
        String email = email(oidc);
        String displayName = displayName(oidc);

        Optional<AppUser> bySub = findByKeycloakSub(sub);
        if (bySub.isPresent()) {
            AppUser user = bySub.get();
            if (!email.equalsIgnoreCase(user.email()) || !Objects.equals(displayName, user.displayName())) {
                updateProfile(user.id(), email, displayName);
            }
            ensureScope(user.id());
            return findById(user.id()).orElse(user);
        }

        Optional<AppUser> byEmail = findByEmail(email);
        if (byEmail.isPresent()) {
            AppUser user = byEmail.get();
            if (user.keycloakSub() != null) {
                throw new APIException(
                        "ACCOUNT_IDENTITY_CONFLICT",
                        "This email address is already linked to another identity.");
            }
            updateIdentity(user.id(), sub, email, displayName);
            ensureScope(user.id());
            return findById(user.id()).orElse(user);
        }

        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.sql("""
                INSERT INTO app_user (
                    id, keycloak_sub, email, display_name, status,
                    gateway_entitlement, created_at, updated_at, version)
                VALUES (
                    :id, :sub, :email, :name, 'ACTIVE',
                    'ABSENT', :now, :now, 0)
                ON CONFLICT DO NOTHING
                """)
                .param("id", id)
                .param("sub", sub)
                .param("email", email)
                .param("name", displayName)
                .param("now", timestamp(now))
                .update();
        ensureScope(id);
        return findById(id).orElseThrow();
    }

    private void updateProfile(UUID id, String email, String displayName) {
        jdbc.sql("""
                UPDATE app_user
                SET email = :email,
                    display_name = :name,
                    updated_at = :now,
                    version = version + 1
                WHERE id = :id
                """)
                .param("id", id)
                .param("email", email)
                .param("name", displayName)
                .param("now", timestamp(clock.instant()))
                .update();
    }

    private void updateIdentity(UUID id, String sub, String email, String displayName) {
        jdbc.sql("""
                UPDATE app_user
                SET keycloak_sub = :sub,
                    email = :email,
                    display_name = :name,
                    updated_at = :now,
                    version = version + 1
                WHERE id = :id
                """)
                .param("id", id)
                .param("sub", sub)
                .param("email", email)
                .param("name", displayName)
                .param("now", timestamp(clock.instant()))
                .update();
    }

    private void ensureScope(UUID userId) {
        jdbc.sql("""
                INSERT INTO app_scope (id, owner_user_id, created_at)
                VALUES (:id, :owner, :now)
                ON CONFLICT (owner_user_id) DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("owner", userId)
                .param("now", timestamp(clock.instant()))
                .update();
    }

    private Optional<AppUser> findByEmail(String email) {
        return jdbc.sql(SELECT + " WHERE lower(u.email) = lower(:email)")
                .param("email", email)
                .query(AppUserService::map)
                .optional();
    }

    private OidcUser currentOidc() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof OidcUser oidc)) {
            throw APIException.signInRequired();
        }
        return oidc;
    }

    private static String email(OidcUser oidc) {
        String email = oidc.getEmail();
        if (email != null && !email.isBlank()) {
            return email.trim();
        }
        Object preferred = oidc.getClaims().get("preferred_username");
        if (preferred != null && !String.valueOf(preferred).isBlank()) {
            return String.valueOf(preferred).trim();
        }
        return oidc.getSubject();
    }

    private static String displayName(OidcUser oidc) {
        String name = oidc.getFullName();
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        Object preferred = oidc.getClaims().get("preferred_username");
        return preferred == null ? null : String.valueOf(preferred).trim();
    }

    private static Instant authTime(OidcUser oidc) {
        try {
            return oidc.getAuthenticatedAt();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static List<String> roles(OidcUser oidc) {
        Object realmAccess = oidc.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> access && access.get("roles") instanceof List<?> values) {
            return values.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static AppUser map(ResultSet rs, int rowNum) throws SQLException {
        return new AppUser(
                rs.getObject("id", UUID.class),
                rs.getString("keycloak_sub"),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("status"),
                rs.getString("gateway_entitlement"),
                rs.getObject("scope_id", UUID.class),
                instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("updated_at", OffsetDateTime.class)),
                instant(rs.getObject("disabled_at", OffsetDateTime.class)),
                rs.getLong("version"));
    }
}
