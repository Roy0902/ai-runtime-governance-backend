package local.governance.gateway.api;

import local.governance.gateway.GatewayCredentialService;
import local.governance.gateway.GatewayCredentialService.Principal;
import local.governance.gateway.GatewayService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/v1")
public class GatewayController {

    private final GatewayCredentialService credentials;
    private final GatewayService gateway;

    public GatewayController(GatewayCredentialService credentials, GatewayService gateway) {
        this.credentials = credentials;
        this.gateway = gateway;
    }

    @PostMapping("/chat/completions")
    public ResponseEntity<StreamingResponseBody> chatCompletions(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "x-api-key", required = false) String apiKey,
            @RequestHeader(value = "X-Governance-Conversation-Id", required = false)
            String conversationId,
            @RequestBody byte[] body) {
        Principal principal = credentials.authenticate(GatewayTokens.resolve(authorization, apiKey));
        GatewayService.ProxyResponse response =
                gateway.exchange(principal, body == null ? new byte[0] : body, conversationId);
        return ResponseEntity.status(response.status())
                .headers(response.headers())
                .body(response.body());
    }
}
