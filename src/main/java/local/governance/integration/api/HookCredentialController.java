package local.governance.integration.api;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import local.governance.integration.HookCredentialService;
import local.governance.integration.HookCredentialService.Issued;
import local.governance.integration.HookCredentialService.Summary;
import local.governance.integration.HookIntegration;
import local.governance.security.AppUserService;
import local.governance.security.AppUserService.AppUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/account/hook-credentials")
public class HookCredentialController {

    private final AppUserService users;
    private final HookCredentialService credentials;

    public HookCredentialController(AppUserService users, HookCredentialService credentials) {
        this.users = users;
        this.credentials = credentials;
    }

    @GetMapping
    public List<Summary> list() {
        AppUser user = users.requireCurrentUser();
        return credentials.list(user.id());
    }

    @PostMapping("/{integration}")
    @ResponseStatus(HttpStatus.CREATED)
    public Issued issue(@PathVariable String integration, HttpServletResponse response) {
        users.requireRecentAuth();
        AppUser user = users.requireCurrentUser();
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return credentials.issue(user.id(), HookIntegration.fromId(integration));
    }

    @DeleteMapping("/{credentialId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID credentialId) {
        users.requireRecentAuth();
        AppUser user = users.requireCurrentUser();
        credentials.revoke(user.id(), credentialId);
    }
}
