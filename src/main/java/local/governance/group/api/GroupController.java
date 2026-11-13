package local.governance.group.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import local.governance.api.APIException;
import local.governance.gateway.GatewayCredentialService;
import local.governance.gateway.GatewayCredentialService.Issued;
import local.governance.gateway.api.GatewaySetupFactory;
import local.governance.gateway.api.GatewaySetupResponse;
import local.governance.group.GroupService;
import local.governance.group.GroupService.GroupDetail;
import local.governance.group.GroupService.GroupSummary;
import local.governance.group.GroupService.IssuedInvite;
import local.governance.provider.ProviderCredentialService;
import local.governance.provider.ProviderCredentialService.Summary;
import local.governance.provider.ProviderKind;
import local.governance.security.AppUserService;
import local.governance.security.AppUserService.AppUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/account/groups")
public class GroupController {

    private final AppUserService users;
    private final GroupService groups;
    private final GatewayCredentialService gatewayKeys;
    private final ProviderCredentialService providers;
    private final GatewaySetupFactory setupFactory;

    public GroupController(
            AppUserService users,
            GroupService groups,
            GatewayCredentialService gatewayKeys,
            ProviderCredentialService providers,
            GatewaySetupFactory setupFactory) {
        this.users = users;
        this.groups = groups;
        this.gatewayKeys = gatewayKeys;
        this.providers = providers;
        this.setupFactory = setupFactory;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GroupSummary create(@RequestBody(required = false) CreateGroupRequest body) {
        users.requireRecentAuth();
        AppUser user = users.requireCurrentUser();
        return groups.create(user.id(), body == null ? null : body.name());
    }

    @GetMapping
    public List<GroupSummary> list() {
        AppUser user = users.requireCurrentUser();
        return groups.list(user.id());
    }

    @GetMapping("/{groupId}")
    public GroupDetail detail(@PathVariable UUID groupId) {
        AppUser user = users.requireCurrentUser();
        return groups.detail(user.id(), groupId);
    }

    @PostMapping("/join")
    public GroupSummary join(@RequestBody(required = false) JoinRequest body) {
        users.requireRecentAuth();
        AppUser user = users.requireCurrentUser();
        return groups.join(user.id(), body == null ? null : body.code());
    }

    @PostMapping("/{groupId}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedInvite invite(
            @PathVariable UUID groupId,
            @RequestBody(required = false) InviteRequest body,
            HttpServletResponse response) {
        users.requireRecentAuth();
        AppUser user = users.requireCurrentUser();
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return groups.createInvite(
                user.id(),
                groupId,
                body == null ? null : body.expiresInHours(),
                body == null ? null : body.maxUses());
    }

    @DeleteMapping("/{groupId}/invites/{inviteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInvite(@PathVariable UUID groupId, @PathVariable UUID inviteId) {
        users.requireRecentAuth();
        AppUser user = users.requireCurrentUser();
        groups.revokeInvite(user.id(), groupId, inviteId);
    }

    @DeleteMapping("/{groupId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable UUID groupId, @PathVariable UUID userId) {
        users.requireRecentAuth();
        AppUser user = users.requireCurrentUser();
        groups.removeMember(user.id(), groupId, userId);
    }

    @PostMapping("/{groupId}/gateway-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public GatewaySetupResponse issueKey(
            @PathVariable UUID groupId,
            @RequestBody(required = false) KeyRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        users.requireRecentAuth();
        AppUser user = users.requireCurrentUser();
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        String name = body == null || body.name() == null || body.name().isBlank() ? "agent" : body.name();
        Issued issued = gatewayKeys.issue(user.id(), groupId, name);
        return setupFactory.build(issued, request);
    }

    @GetMapping("/{groupId}/provider-credentials")
    public List<Summary> listProviders(@PathVariable UUID groupId) {
        AppUser user = users.requireCurrentUser();
        groups.requireOwner(groupId, user.id());
        return providers.listForGroup(groupId);
    }

    @PutMapping("/{groupId}/provider-credentials/{provider}")
    public Summary saveProvider(
            @PathVariable UUID groupId,
            @PathVariable String provider,
            @RequestBody(required = false) ProviderRequest body,
            HttpServletResponse response) {
        users.requireRecentAuth();
        AppUser user = users.requireCurrentUser();
        groups.requireOwner(groupId, user.id());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        ProviderRequest request = body == null ? new ProviderRequest(null, null, null) : body;
        return providers.saveForGroup(
                groupId, kind(provider), request.accessKey(), request.secretKey(), request.name());
    }

    @DeleteMapping("/{groupId}/provider-credentials/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProvider(@PathVariable UUID groupId, @PathVariable String provider) {
        users.requireRecentAuth();
        AppUser user = users.requireCurrentUser();
        groups.requireOwner(groupId, user.id());
        providers.deleteForGroup(groupId, kind(provider));
    }

    private static ProviderKind kind(String value) {
        return ProviderKind.parse(value)
                .orElseThrow(() -> APIException.invalid("provider", "Unsupported provider."));
    }

    public record CreateGroupRequest(String name) {}

    public record JoinRequest(String code) {}

    public record InviteRequest(Integer expiresInHours, Integer maxUses) {}

    public record KeyRequest(String name) {}

    public record ProviderRequest(String accessKey, String secretKey, String name) {}
}
