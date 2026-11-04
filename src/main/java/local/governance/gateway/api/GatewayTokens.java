package local.governance.gateway.api;

import local.governance.api.APIException;

public final class GatewayTokens {

    private GatewayTokens() {}

    /**
     * Accepts the two common OpenAI-compatible client header styles. A missing or
     * malformed value is treated as an invalid gateway credential, never as an
     * anonymous request.
     */
    public static String resolve(String authorization, String apiKey) {
        String token = null;
        if (authorization != null && !authorization.isBlank()) {
            String value = authorization.trim();
            if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
                token = value.substring(7).trim();
            } else {
                throw invalid();
            }
        }
        if (token == null && apiKey != null && !apiKey.isBlank()) {
            token = apiKey.trim();
        }
        if (token == null || token.isEmpty()) {
            throw invalid();
        }
        return token;
    }

    private static APIException invalid() {
        return new APIException(
                "GATEWAY_CREDENTIAL_INVALID",
                "The gateway key is invalid or revoked.");
    }
}
