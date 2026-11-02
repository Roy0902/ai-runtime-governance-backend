package local.governance.auth.session;

import local.governance.api.ApiDtos.AccountDto;
import local.governance.api.ApiDtos.ContextDto;
import local.governance.api.ApiDtos.SessionDto;
import local.governance.auth.persistence.AuthRepository.AccountRow;
import org.springframework.stereotype.Component;

@Component
public class ContextMapper {

    public ContextDto context(SessionService.Current current, AccountRow account) {
        return switch (current.context()) {
            case "HUMAN" -> new ContextDto("HUMAN", current.csrfToken(), null, null, account(account, current));
            case "SETUP" -> new ContextDto(
                    "SETUP",
                    current.csrfToken(),
                    current.row().absoluteExpiresAt(),
                    current.purpose(),
                    null);
            default -> new ContextDto("ANONYMOUS", current.csrfToken(), null, null, null);
        };
    }

    public AccountDto account(AccountRow account, SessionService.Current current) {
        return new AccountDto(
                account.id(),
                account.email(),
                account.emailVerifiedAt() != null,
                "ENROLLED",
                account.scopeId(),
                account.gatewayEntitlement(),
                new SessionDto(
                        current.row().createdAt(),
                        current.row().absoluteExpiresAt(),
                        current.row().idleExpiresAt(),
                        current.row().recentAuthExpiresAt()));
    }
}
