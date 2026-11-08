package local.governance.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import local.governance.authentication.session.SessionService;
import local.governance.security.AppUserService.AppUser;
import local.governance.security.AppUserService.NativeUserPrincipal;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bridges the existing native __Host-arg-session cookie into Spring Security so
 * native and Keycloak sessions can use the same application API.
 */
public class NativeSessionAuthenticationFilter extends OncePerRequestFilter {

    private final SessionService sessions;
    private final AppUserService users;

    public NativeSessionAuthenticationFilter(SessionService sessions, AppUserService users) {
        this.sessions = sessions;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing != null && !(existing instanceof AnonymousAuthenticationToken)) {
            chain.doFilter(request, response);
            return;
        }

        Optional<SessionService.Current> current = sessions.read(request);
        if (current.isEmpty()
                || !"HUMAN".equals(current.get().context())
                || current.get().accountId() == null) {
            chain.doFilter(request, response);
            return;
        }

        Optional<AppUser> found = users.findById(current.get().accountId());
        if (found.isEmpty() || !"ACTIVE".equals(found.get().status())) {
            chain.doFilter(request, response);
            return;
        }

        AppUser user = found.get();
        NativeUserPrincipal principal = new NativeUserPrincipal(
                user.id(),
                user.email(),
                user.scopeId(),
                current.get().csrfToken(),
                current.get().row().recentAuthExpiresAt());
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }
}
