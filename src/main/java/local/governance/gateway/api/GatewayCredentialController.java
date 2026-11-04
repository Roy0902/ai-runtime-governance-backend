package local.governance.gateway.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import local.governance.api.APIException;
import local.governance.authentication.session.SessionService;
import local.governance.config.GovernanceProperties;
import local.governance.gateway.GatewayCredentialService;
import local.governance.gateway.GatewayCredentialService.Issued;
import local.governance.gateway.GatewayCredentialService.Summary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/account/gateway-keys")
public class GatewayCredentialController {

    private final SessionService sessions;
    private final GatewayCredentialService credentials;
    private final GovernanceProperties properties;
    private final ObjectMapper objectMapper;

    public GatewayCredentialController(
            SessionService sessions,
            GatewayCredentialService credentials,
            GovernanceProperties properties,
            ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.credentials = credentials;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<Summary> list(HttpServletRequest request) {
        SessionService.Current current = sessions.requireHuman(request);
        return credentials.list(current.accountId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GatewaySetupResponse issue(
            @RequestBody(required = false) CreateRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        SessionService.Current current = sessions.requireHuman(request);
        sessions.requireRecentAuth(current);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");

        String name = body == null || body.name() == null || body.name().isBlank()
                ? "default"
                : body.name();
        Issued issued = credentials.issue(current.accountId(), name);
        return setup(issued, request);
    }

    @DeleteMapping("/{credentialId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID credentialId, HttpServletRequest request) {
        sessions.requireCsrfIfBrowser(request);
        SessionService.Current current = sessions.requireHuman(request);
        sessions.requireRecentAuth(current);
        credentials.revoke(current.accountId(), credentialId);
    }

    private GatewaySetupResponse setup(Issued issued, HttpServletRequest request) {
        List<String> models = properties.getGateway().modelList();
        String model = models.isEmpty() ? "model-id" : models.getFirst();
        String baseUrl = gatewayBaseUrl(request);
        return new GatewaySetupResponse(
                issued.id(),
                issued.name(),
                issued.token(),
                issued.createdAt(),
                baseUrl,
                "/chat/completions",
                models,
                json(openCodeConfig(baseUrl, issued.token(), model)),
                json(vsCodeConfig(baseUrl, model)));
    }

    private String gatewayBaseUrl(HttpServletRequest request) {
        String configured = properties.getGateway().getPublicBaseUrl();
        if (configured != null && !configured.isBlank()) {
            return trimTrailingSlash(configured) + "/v1";
        }
        return trimTrailingSlash(ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString())
                + "/v1";
    }

    private static String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static Map<String, Object> openCodeConfig(String baseUrl, String token, String model) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("baseURL", baseUrl);
        options.put("apiKey", token);
        options.put("headers", Map.of("X-Governance-Conversation-Id", "opencode"));
        Map<String, Object> provider = new LinkedHashMap<>();
        provider.put("npm", "@ai-sdk/openai-compatible");
        provider.put("name", "AI Runtime Governance");
        provider.put("options", options);
        provider.put("models", Map.of(model, Map.of("name", model + " via AI Runtime Governance")));
        return Map.of("provider", Map.of("governance", provider));
    }

    private static List<Map<String, Object>> vsCodeConfig(String baseUrl, String model) {
        return List.of(Map.of(
                "name", "AI Runtime Governance",
                "vendor", "customendpoint",
                "apiKey", "${input:governanceKey}",
                "apiType", "chat-completions",
                "models", List.of(Map.of(
                        "id", model,
                        "name", model + " via AI Runtime Governance",
                        "url", baseUrl + "/chat/completions",
                        "toolCalling", true,
                        "vision", false,
                        "maxInputTokens", 128_000,
                        "maxOutputTokens", 16_000))));
    }

    private String json(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw APIException.unavailable();
        }
    }

    public record CreateRequest(String name) {}
}
