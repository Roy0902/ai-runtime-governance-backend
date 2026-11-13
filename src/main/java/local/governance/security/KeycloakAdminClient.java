package local.governance.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Minimal Keycloak Admin API client used only for application-controlled
 * registration. The backend service account has manage-users, view-users and
 * query-users on the arg realm.
 */
@Service
@Profile("keycloak")
public class KeycloakAdminClient {

    public static class RegistrationException extends RuntimeException {
        private final String field;

        public RegistrationException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    private final RestClient http = RestClient.create();
    private final ObjectMapper objectMapper;
    private final String issuer;
    private final String adminBase;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private volatile String cachedToken;
    private volatile Instant cachedTokenExpiresAt = Instant.EPOCH;

    public KeycloakAdminClient(
            @Value("${governance.keycloak.issuer-uri}") String issuer,
            @Value("${governance.keycloak.admin-client-id}") String clientId,
            @Value("${governance.keycloak.admin-client-secret}") String clientSecret,
            ObjectMapper objectMapper) {
        this.issuer = trimTrailingSlash(issuer);
        int marker = this.issuer.indexOf("/realms/");
        if (marker < 0) {
            throw new IllegalStateException("Keycloak issuer must include /realms/: " + issuer);
        }
        String server = this.issuer.substring(0, marker);
        String realm = this.issuer.substring(marker + "/realms/".length());
        this.adminBase = server + "/admin/realms/" + realm;
        this.tokenUri = this.issuer + "/protocol/openid-connect/token";
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.objectMapper = objectMapper;
    }

    public void createUserAndSendVerification(
            String email, String password, String firstName, String lastName) {
        String token = accessToken();
        Map<String, Object> body = Map.of(
                "username", email,
                "email", email,
                "firstName", firstName,
                "lastName", lastName,
                "enabled", true,
                "emailVerified", false,
                "credentials", List.of(Map.of(
                        "type", "password",
                        "value", password,
                        "temporary", false)),
                "requiredActions", List.of(
                        "VERIFY_EMAIL",
                        "CONFIGURE_TOTP",
                        "CONFIGURE_RECOVERY_AUTHN_CODES"));
        try {
            ResponseEntity<String> response = http.post()
                    .uri(adminBase + "/users")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(write(body))
                    .retrieve()
                    .toEntity(String.class);
            String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
            if (location != null && !location.isBlank()) {
                sendVerification(location, token);
            }
        } catch (HttpClientErrorException.Conflict alreadyExists) {
            throw new RegistrationException(
                    "email",
                    "An account with this email already exists. Sign in or reset your password.");
        } catch (HttpClientErrorException.BadRequest invalid) {
            throw new RegistrationException(
                    "password",
                    "Password or account details do not meet the policy.");
        }
    }

    private void sendVerification(String userLocation, String token) {
        http.put()
                .uri(userLocation + "/send-verify-email")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .toBodilessEntity();
    }

    private String accessToken() {
        if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiresAt.minusSeconds(30))) {
            return cachedToken;
        }
        synchronized (this) {
            if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiresAt.minusSeconds(30))) {
                return cachedToken;
            }
            String form = "grant_type=client_credentials"
                    + "&client_id=" + encode(clientId)
                    + "&client_secret=" + encode(clientSecret);
            String response = http.post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            try {
                JsonNode json = objectMapper.readTree(response);
                String token = json.path("access_token").asText();
                long expiresIn = json.path("expires_in").asLong(60);
                cachedToken = token;
                cachedTokenExpiresAt = Instant.now().plusSeconds(expiresIn);
                return token;
            } catch (Exception exception) {
                throw new IllegalStateException("Keycloak token response could not be parsed.", exception);
            }
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize Keycloak request.", exception);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
