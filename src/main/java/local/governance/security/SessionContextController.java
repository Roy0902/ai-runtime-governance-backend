package local.governance.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import local.governance.authentication.api.response.AuthenticationContextResponse;
import local.governance.authentication.application.AuthenticationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified session context for both native and Keycloak sessions. The web app
 * calls this endpoint instead of provider-specific context routes.
 */
@RestController
@RequestMapping("/api/v1/session")
public class SessionContextController {

    private final AuthenticationService nativeAuthentication;
    private final ObjectProvider<KeycloakBffController> keycloak;
    private final boolean googleLoginEnabled;

    public SessionContextController(
            AuthenticationService nativeAuthentication,
            ObjectProvider<KeycloakBffController> keycloak,
            @Value("${governance.keycloak.google-client-id:}") String googleClientId) {
        this.nativeAuthentication = nativeAuthentication;
        this.keycloak = keycloak;
        this.googleLoginEnabled = googleClientId != null && !googleClientId.isBlank();
    }

    @GetMapping("/context")
    public Map<String, Object> context(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof OidcUser oidc) {
            KeycloakBffController controller = keycloak.getIfAvailable();
            if (controller != null) {
                Map<String, Object> body = new LinkedHashMap<>(controller.context(oidc));
                body.put("csrfToken", keycloakCsrf(request));
                body.put("provider", "keycloak");
                body.put("googleLoginEnabled", googleLoginEnabled);
                return body;
            }
        }

        AuthenticationContextResponse nativeContext = nativeAuthentication.context(request, response);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", nativeContext.state());
        body.put("csrfToken", nativeContext.csrfToken());
        body.put("setupExpiresAt", nativeContext.setupExpiresAt());
        body.put("enrollmentPurpose", nativeContext.enrollmentPurpose());
        body.put("account", nativeContext.account());
        body.put("provider", nativeContext.account() == null ? null : "native");
        body.put("googleLoginEnabled", googleLoginEnabled);
        if (nativeContext.account() == null) {
            body.put("roles", List.of());
        } else if ("GRANTED".equals(nativeContext.account().gatewayEntitlement())) {
            body.put("roles", List.of("user", "gateway-user"));
        } else {
            body.put("roles", List.of("user"));
        }
        return body;
    }

    private static String keycloakCsrf(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        Object existing = session.getAttribute(UnifiedCsrfFilter.keycloakCsrfAttribute());
        if (existing != null) {
            return existing.toString();
        }
        String token = UUID.randomUUID().toString();
        session.setAttribute(UnifiedCsrfFilter.keycloakCsrfAttribute(), token);
        return token;
    }
}
