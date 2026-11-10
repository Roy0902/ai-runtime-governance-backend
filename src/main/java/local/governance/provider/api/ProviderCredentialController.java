package local.governance.provider.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import local.governance.api.APIException;
import local.governance.authentication.persistence.AuthenticationRepository.AccountRow;
import local.governance.authentication.session.SessionService;
import local.governance.provider.ProviderCredentialService;
import local.governance.provider.ProviderCredentialService.Summary;
import local.governance.provider.ProviderKind;
import local.governance.security.AccountContextService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets a signed-in person store their own LangSmith / Langfuse credentials.
 *
 * <p>The secret is sealed before it reaches the database and is never returned
 * by any endpoint. Clients only ever receive their own gateway access key.
 */
@RestController
@RequestMapping("/api/v1/account/provider-credentials")
public class ProviderCredentialController {

    private final AccountContextService users;
    private final SessionService sessions;
    private final ProviderCredentialService credentials;

    public ProviderCredentialController(
            AccountContextService users,
            SessionService sessions,
            ProviderCredentialService credentials) {
        this.users = users;
        this.sessions = sessions;
        this.credentials = credentials;
    }

    @GetMapping
    public List<Summary> list(HttpServletRequest request) {
        AccountRow user = users.requireCurrentUser(request);
        return credentials.list(user.id());
    }

    @PutMapping("/{provider}")
    public Summary save(
            @PathVariable String provider,
            @RequestBody(required = false) SaveRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        SessionService.Current current = sessions.requireHuman(request);
        sessions.requireCsrfIfBrowser(request);
        sessions.requireRecentAuth(current);
        AccountRow user = users.requireActive(current.accountId());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        SaveRequest save = body == null ? new SaveRequest(null, null, null) : body;
        return credentials.save(
                user.id(), kind(provider), save.accessKey(), save.secretKey(), save.name());
    }

    @DeleteMapping("/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String provider, HttpServletRequest request) {
        SessionService.Current current = sessions.requireHuman(request);
        sessions.requireCsrfIfBrowser(request);
        sessions.requireRecentAuth(current);
        AccountRow user = users.requireActive(current.accountId());
        credentials.delete(user.id(), kind(provider));
    }

    private static ProviderKind kind(String value) {
        return ProviderKind.parse(value)
                .orElseThrow(() -> APIException.invalid("provider", "Unsupported provider."));
    }

    public record SaveRequest(String accessKey, String secretKey, String name) {}
}
