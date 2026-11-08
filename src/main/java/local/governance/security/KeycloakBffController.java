package local.governance.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import local.governance.api.APIException;
import local.governance.authentication.domain.EmailPolicy;
import local.governance.authentication.domain.PasswordPolicy;
import local.governance.security.AppUserService.AppUser;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * BFF authentication endpoints used by the web app.
 */
@RestController
@RequestMapping("/api/v1/keycloak")
@Profile("keycloak")
public class KeycloakBffController {

    private final AppUserService users;
    private final KeycloakAdminClient admin;

    public KeycloakBffController(AppUserService users, KeycloakAdminClient admin) {
        this.users = users;
        this.admin = admin;
    }

    public record RegisterRequest(String email, String password, String firstName, String lastName) {}

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> register(@RequestBody RegisterRequest body) {
        if (body == null) {
            throw APIException.invalid("email", "Enter your email address.");
        }
        String email;
        try {
            email = EmailPolicy.normalize(body.email());
        } catch (RuntimeException exception) {
            throw APIException.invalid("email", "Enter a valid email address.");
        }
        PasswordPolicy.validateNew(body.password(), "password");
        String firstName = body.firstName() == null || body.firstName().isBlank()
                ? "User"
                : body.firstName().trim();
        String lastName = body.lastName() == null ? "" : body.lastName().trim();
        try {
            admin.createUserAndSendVerification(email, body.password(), firstName, lastName);
        } catch (KeycloakAdminClient.RegistrationException exception) {
            throw APIException.invalid(exception.field(), exception.getMessage());
        }
        return Map.of("accepted", true);
    }

    @GetMapping("/login")
    public void login(
            @RequestParam(value = "stepUp", required = false) String stepUp,
            HttpServletResponse response) throws IOException {
        String target = "/api/v1/keycloak/authorize/keycloak";
        if ("1".equals(stepUp)) {
            target += "?stepUp=1";
        }
        response.sendRedirect(target);
    }

    @GetMapping("/login/google")
    public void loginWithGoogle(HttpServletResponse response) throws IOException {
        response.sendRedirect("/api/v1/keycloak/authorize/keycloak?kc_idp_hint=google");
    }

    @GetMapping("/context")
    public Map<String, Object> context(@AuthenticationPrincipal OidcUser oidcUser) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (oidcUser == null) {
            body.put("state", "ANONYMOUS");
            body.put("account", null);
            return body;
        }

        AppUser user = users.ensureUser(oidcUser);
        body.put("state", "HUMAN");
        body.put("subject", user.keycloakSub());
        body.put("email", user.email());
        body.put("name", user.displayName());
        body.put("roles", users.currentRoles());
        body.put("account", account(user, oidcUser));
        return body;
    }

    private Map<String, Object> account(AppUser user, OidcUser oidcUser) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("accountId", user.id().toString());
        account.put("email", user.email());
        account.put("emailVerified", true);
        account.put("mfaState", "ENROLLED");
        account.put("scopeId", user.scopeId() == null ? null : user.scopeId().toString());
        account.put("gatewayEntitlement", user.gatewayEntitlement());
        account.put("session", session(oidcUser));
        return account;
    }

    private Map<String, Object> session(OidcUser oidcUser) {
        Instant authTime = users.currentAuthTime();
        Instant expiresAt = oidcUser.getIdToken() == null ? null : oidcUser.getIdToken().getExpiresAt();
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("createdAt", authTime == null ? null : authTime.toString());
        session.put("absoluteExpiresAt", expiresAt == null ? null : expiresAt.toString());
        session.put("idleExpiresAt", expiresAt == null ? null : expiresAt.toString());
        session.put("recentAuthExpiresAt",
                authTime == null ? null : authTime.plus(Duration.ofMinutes(10)).toString());
        return session;
    }
}
