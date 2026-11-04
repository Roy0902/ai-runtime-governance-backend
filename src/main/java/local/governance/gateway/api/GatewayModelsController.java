package local.governance.gateway.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import local.governance.api.APIException;
import local.governance.config.GovernanceProperties;
import local.governance.gateway.GatewayCredentialService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GatewayModelsController {

    private final GatewayCredentialService credentials;
    private final GovernanceProperties properties;

    public GatewayModelsController(
            GatewayCredentialService credentials,
            GovernanceProperties properties) {
        this.credentials = credentials;
        this.properties = properties;
    }

    @GetMapping("/v1/models")
    public Map<String, Object> list(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "x-api-key", required = false) String apiKey) {
        if (!properties.getGateway().isEnabled()) {
            throw new APIException("GATEWAY_DISABLED", "The model gateway is disabled.");
        }
        credentials.authenticate(GatewayTokens.resolve(authorization, apiKey));
        List<Map<String, Object>> data = properties.getGateway().modelList().stream()
                .map(GatewayModelsController::model)
                .toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("object", "list");
        response.put("data", data);
        return response;
    }

    private static Map<String, Object> model(String id) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", id);
        model.put("object", "model");
        model.put("created", 0);
        model.put("owned_by", "governance");
        return model;
    }
}
