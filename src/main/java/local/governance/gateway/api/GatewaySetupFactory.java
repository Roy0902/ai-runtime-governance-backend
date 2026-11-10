package local.governance.gateway.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import local.governance.api.APIException;
import local.governance.config.GovernanceProperties;
import local.governance.gateway.GatewayCredentialService.Issued;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Builds the one-time setup bundle returned when a gateway key is issued. */
@Component
public class GatewaySetupFactory {

    private final GovernanceProperties properties;
    private final ObjectMapper objectMapper;

    public GatewaySetupFactory(GovernanceProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public GatewaySetupResponse build(Issued issued, HttpServletRequest request) {
        List<String> models = properties.getGateway().modelList();
        String model = models.isEmpty() ? "model-id" : models.getFirst();
        String baseUrl = baseUrl(request);
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

    private String baseUrl(HttpServletRequest request) {
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
        return Map.of(
                "$schema", "https://opencode.ai/config.json",
                "model", "governance/" + model,
                "provider", Map.of("governance", provider));
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
}
