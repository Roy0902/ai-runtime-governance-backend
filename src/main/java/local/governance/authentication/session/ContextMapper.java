package local.governance.authentication.session;

import local.governance.authentication.api.response.AccountResponse;
import local.governance.authentication.api.response.AuthenticationContextResponse;
import local.governance.authentication.api.response.SessionLifetimeResponse;
import local.governance.authentication.persistence.AuthenticationRepository.AccountRow;
import org.springframework.stereotype.Component;

@Component
public class ContextMapper {

    public AuthenticationContextResponse context(SessionService.Current current, AccountRow account) {
        return switch (current.context()) {
            case "HUMAN" -> new AuthenticationContextResponse("HUMAN", current.csrfToken(), null, null, account(account, current));
            case "SETUP" -> new AuthenticationContextResponse(
                    "SETUP",
                    current.csrfToken(),
                    current.row().absoluteExpiresAt(),
                    current.purpose(),
                    null);
            default -> new AuthenticationContextResponse("ANONYMOUS", current.csrfToken(), null, null, null);
        };
    }

    public AccountResponse account(AccountRow account, SessionService.Current current) {
        return new AccountResponse(
                account.id(),
                account.email(),
                account.emailVerifiedAt() != null,
                "ENROLLED",
                account.scopeId(),
                account.gatewayEntitlement(),
                new SessionLifetimeResponse(
                        current.row().createdAt(),
                        current.row().absoluteExpiresAt(),
                        current.row().idleExpiresAt(),
                        current.row().recentAuthExpiresAt()));
    }
}
