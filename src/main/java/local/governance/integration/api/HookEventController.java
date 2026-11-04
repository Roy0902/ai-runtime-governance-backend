package local.governance.integration.api;

import local.governance.api.APIException;
import local.governance.integration.HookIntegration;
import local.governance.integration.HookIngestionService;
import local.governance.integration.HookIngestionService.Receipt;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hooks")
public class HookEventController {

    private final HookIngestionService ingestion;

    public HookEventController(HookIngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @PostMapping("/{integration}/events")
    public Receipt ingest(
            @PathVariable String integration,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody HookIngestRequest request) {
        HookIntegration source = HookIntegration.fromId(integration);
        return ingestion.ingest(source, bearerToken(authorization), request);
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw invalidCredential();
        }
        String token = authorization.substring("Bearer ".length());
        if (token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) {
            throw invalidCredential();
        }
        return token;
    }

    private static APIException invalidCredential() {
        return new APIException("HOOK_CREDENTIAL_INVALID", "The integration credential is invalid or revoked.");
    }
}
