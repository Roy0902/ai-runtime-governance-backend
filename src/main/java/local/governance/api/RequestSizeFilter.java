package local.governance.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces the approved JSON content type and 16,384-byte request limit for API
 * mutations. A declared Content-Length is checked before the body is read; the
 * acceptance client always declares it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestSizeFilter extends OncePerRequestFilter {

    static final int MAX_BYTES = 16_384;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();
        boolean mutation = path.startsWith("/api/")
                && ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method));

        if (mutation) {
            String contentType = request.getContentType();
            if (contentType == null || !contentType.toLowerCase().startsWith(MediaType.APPLICATION_JSON_VALUE)) {
                writeFailure(response, 415, "REQUEST_INVALID", "Use application/json.");
                return;
            }
            if (request.getContentLengthLong() > MAX_BYTES) {
                writeFailure(response, 413, "REQUEST_INVALID", "Request body is too large.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void writeFailure(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        UUID correlationId = UUID.randomUUID();
        response.setHeader("X-Correlation-Id", correlationId.toString());
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message
                + "\",\"correlationId\":\"" + correlationId + "\"}");
    }
}
