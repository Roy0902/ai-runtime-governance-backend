package local.governance.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import local.governance.security.AppUserService.NativeUserPrincipal;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One CSRF check for both authentication mechanisms. Native sessions use the
 * token already stored in web_session; Keycloak sessions use the token stored
 * in the BFF HttpSession by the unified context endpoint.
 */
public class UnifiedCsrfFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String method = request.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method) || "TRACE".equals(method)) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        if (path.startsWith("/api/v1/auth/")
                || path.equals("/api/v1/keycloak/login")
                || path.equals("/api/v1/keycloak/register")
                || path.equals("/api/v1/keycloak/callback")
                || path.equals("/api/v1/keycloak/logout")
                || path.startsWith("/api/v1/hooks/")
                || path.startsWith("/actuator/")) {
            chain.doFilter(request, response);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            chain.doFilter(request, response);
            return;
        }

        String expected = null;
        if (authentication.getPrincipal() instanceof NativeUserPrincipal nativePrincipal) {
            expected = nativePrincipal.csrfToken();
        } else if (authentication.getPrincipal() instanceof OidcUser) {
            var httpSession = request.getSession(false);
            Object value = httpSession == null ? null : httpSession.getAttribute(keycloakCsrfAttribute());
            expected = value == null ? null : value.toString();
        }

        String supplied = request.getHeader("X-CSRF-Token");
        if (expected == null || supplied == null || !matches(expected, supplied)) {
            writeFailure(response);
            return;
        }
        chain.doFilter(request, response);
    }

    public static String keycloakCsrfAttribute() {
        return "arg.keycloak.csrf";
    }

    private static boolean matches(String expected, String supplied) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFailure(HttpServletResponse response) throws IOException {
        UUID correlationId = UUID.randomUUID();
        response.setStatus(403);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Correlation-Id", correlationId.toString());
        response.getWriter().write("{\"code\":\"CSRF_REJECTED\",\"message\":\"Your session changed. "
                + "Reload the page and try again.\",\"correlationId\":\"" + correlationId + "\"}");
    }
}
