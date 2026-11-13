package local.governance.gateway.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import local.governance.gateway.GatewayCredentialService;
import local.governance.gateway.GatewayCredentialService.Issued;
import local.governance.gateway.GatewayCredentialService.Summary;
import local.governance.security.AppUserService;
import local.governance.security.AppUserService.AppUser;
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

    private final AppUserService users;
    private final GatewayCredentialService credentials;
    private final GatewaySetupFactory setupFactory;

    public GatewayCredentialController(
            AppUserService users,
            GatewayCredentialService credentials,
            GatewaySetupFactory setupFactory) {
        this.users = users;
        this.credentials = credentials;
        this.setupFactory = setupFactory;
    }

    @GetMapping
    public List<Summary> list() {
        AppUser user = users.requireCurrentUser();
        return credentials.list(user.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GatewaySetupResponse issue(
            @RequestBody(required = false) CreateRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        users.requireRecentAuth();
        AppUser user = users.requireCurrentUser();
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");

        String name = body == null || body.name() == null || body.name().isBlank()
                ? "default"
                : body.name();
        Issued issued = credentials.issue(user.id(), name);
        return setupFactory.build(issued, request);
    }

    @DeleteMapping("/{credentialId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID credentialId) {
        users.requireRecentAuth();
        AppUser user = users.requireCurrentUser();
        credentials.revoke(user.id(), credentialId);
    }

    public record CreateRequest(String name) {}
}
