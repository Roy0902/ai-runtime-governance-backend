package local.governance.gateway.api;

import local.governance.api.APIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * OpenAI-compatible clients expect {@code {"error": {...}}} rather than the
 * account API's failure envelope. This advice is limited to the gateway
 * controllers so account endpoints keep their existing contract.
 */
@RestControllerAdvice(assignableTypes = {GatewayController.class, GatewayModelsController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    @ExceptionHandler(APIException.class)
    public ResponseEntity<GatewayErrorResponse> handle(APIException exception) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
        if (exception.retryAfterSeconds() != null) {
            headers.set(HttpHeaders.RETRY_AFTER, exception.retryAfterSeconds().toString());
        }
        return new ResponseEntity<>(
                body(exception.getMessage(), type(exception.status()), exception.code()),
                headers,
                exception.status());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<GatewayErrorResponse> malformed() {
        return response(
                HttpStatus.BAD_REQUEST,
                "The request body is not valid JSON.",
                "REQUEST_INVALID");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<GatewayErrorResponse> unexpected(Exception exception) {
        log.error("unhandled gateway failure", exception);
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "The gateway failed to complete the request.",
                "INTERNAL_ERROR");
    }

    private static ResponseEntity<GatewayErrorResponse> response(
            HttpStatus status,
            String message,
            String code) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body(message, type(status), code));
    }

    private static GatewayErrorResponse body(String message, String type, String code) {
        return new GatewayErrorResponse(new GatewayErrorResponse.Error(message, type, null, code));
    }

    private static String type(HttpStatus status) {
        if (status == HttpStatus.UNAUTHORIZED) {
            return "authentication_error";
        }
        if (status == HttpStatus.FORBIDDEN) {
            return "permission_error";
        }
        if (status == HttpStatus.NOT_FOUND) {
            return "not_found_error";
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            return "rate_limit_error";
        }
        if (status.is5xxServerError()) {
            return "api_error";
        }
        return "invalid_request_error";
    }
}
