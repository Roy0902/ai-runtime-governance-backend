package local.governance.gateway.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import local.governance.authentication.session.SessionService;
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

@RestController
@RequestMapping("/api/v1/account/gateway-keys")
public class GatewayCredentialController {

    private final SessionService sessions;
    private final GatewayCredentialService credentials;
    private final GatewaySetupFactory setupFactory;

    public GatewayCredentialController(
            SessionService sessions,
            GatewayCredentialService credentials,
            GatewaySetupFactory setupFactory) {
        this.sessions = sessions;
        this.credentials = credentials;
        this.setupFactory = setupFactory;
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
        return setupFactory.build(issued, request);
    }

    @DeleteMapping("/{credentialId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID credentialId, HttpServletRequest request) {
        sessions.requireCsrfIfBrowser(request);
        SessionService.Current current = sessions.requireHuman(request);
        sessions.requireRecentAuth(current);
        credentials.revoke(current.accountId(), credentialId);
    }

    public record CreateRequest(String name) {}
}
