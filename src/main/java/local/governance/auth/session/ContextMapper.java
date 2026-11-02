package local.governance.auth.session;

import local.governance.api.AccountDTO;
import local.governance.api.ContextDTO;
import local.governance.api.SessionDTO;
import local.governance.auth.persistence.AuthRepository.AccountRow;
import org.springframework.stereotype.Component;

@Component
public class ContextMapper {

    public ContextDTO context(SessionService.Current current, AccountRow account) {
        return switch (current.context()) {
            case "HUMAN" -> new ContextDTO("HUMAN", current.csrfToken(), null, null, account(account, current));
            case "SETUP" -> new ContextDTO(
                    "SETUP",
                    current.csrfToken(),
                    current.row().absoluteExpiresAt(),
                    current.purpose(),
                    null);
            default -> new ContextDTO("ANONYMOUS", current.csrfToken(), null, null, null);
        };
    }

    public AccountDTO account(AccountRow account, SessionService.Current current) {
        return new AccountDTO(
                account.id(),
                account.email(),
                account.emailVerifiedAt() != null,
                "ENROLLED",
                account.scopeId(),
                account.gatewayEntitlement(),
                new SessionDTO(
                        current.row().createdAt(),
                        current.row().absoluteExpiresAt(),
                        current.row().idleExpiresAt(),
                        current.row().recentAuthExpiresAt()));
    }
}
