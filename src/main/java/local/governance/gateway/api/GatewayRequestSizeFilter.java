package local.governance.gateway.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import local.governance.config.GovernanceProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The account API's size filter only covers {@code /api/}. Gateway traffic is
 * larger and has its own bound, checked before the body is materialised.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class GatewayRequestSizeFilter extends OncePerRequestFilter {

    private final GovernanceProperties properties;

    public GatewayRequestSizeFilter(GovernanceProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/v1/") || !"POST".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String contentType = request.getContentType();
        if (contentType == null
                || !contentType.toLowerCase().startsWith(MediaType.APPLICATION_JSON_VALUE)) {
            failure(response, 415, "invalid_request_error", "Use application/json.");
            return;
        }
        long declared = request.getContentLengthLong();
        if (declared > properties.getGateway().getMaxRequestBytes()) {
            failure(response, 413, "invalid_request_error", "The request body is too large.");
            return;
        }
        chain.doFilter(request, response);
    }

    private static void failure(
            HttpServletResponse response,
            int status,
            String type,
            String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"error\":{\"message\":\""
                + message
                + "\",\"type\":\""
                + type
                + "\",\"param\":null,\"code\":\"REQUEST_INVALID\"}}");
    }
}
