package local.governance.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import local.governance.api.APIException;
import local.governance.authentication.persistence.AuthenticationRepository;
import local.governance.authentication.persistence.AuthenticationRepository.AccountRow;
import local.governance.authentication.session.SessionService;
import org.springframework.stereotype.Service;

/**
 * Native-session adapter for account-scoped controllers and services.
 *
 * <p>The output-timeline backend authenticates humans with its own session
 * cookies instead of an external identity provider. This service is the single
 * place that turns a session into an account row, so provider, group and
 * gateway code can share the same status and eligibility rules.
 */
@Service
public class AccountContextService {

    private final SessionService sessions;
    private final AuthenticationRepository accounts;

    public AccountContextService(SessionService sessions, AuthenticationRepository accounts) {
        this.sessions = sessions;
        this.accounts = accounts;
    }

    public AccountRow requireCurrentUser(HttpServletRequest request) {
        SessionService.Current current = sessions.requireHuman(request);
        return requireActive(current.accountId());
    }

    public void requireRecentAuth(HttpServletRequest request) {
        SessionService.Current current = sessions.requireHuman(request);
        sessions.requireRecentAuth(current);
    }

    public AccountRow requireActive(UUID accountId) {
        AccountRow account = accounts.accountById(accountId).orElseThrow(APIException::signInRequired);
        if ("DISABLED".equals(account.status())) {
            throw new APIException("FORBIDDEN", "This account is disabled.");
        }
        return account;
    }

    public AccountRow requireByEmail(String email) {
        if (email == null || email.isBlank()) {
            throw APIException.signInRequired();
        }
        AccountRow account = accounts.accountByEmail(email.trim()).orElseThrow(APIException::signInRequired);
        if ("DISABLED".equals(account.status())) {
            throw new APIException("FORBIDDEN", "This account is disabled.");
        }
        return account;
    }

    /**
     * Gateway use requires a verified address and an enrolled second factor.
     */
    public AccountRow requireEnrolled(UUID accountId) {
        AccountRow account = requireActive(accountId);
        if (!enrolled(account)) {
            throw new APIException(
                    "GATEWAY_NOT_ENTITLED",
                    "Verify your email and enroll a second factor before using the model gateway.");
        }
        return account;
    }

    public UUID requireScopeId(HttpServletRequest request) {
        AccountRow account = requireCurrentUser(request);
        if (account.scopeId() == null) {
            throw APIException.notFound();
        }
        return account.scopeId();
    }

    public static boolean enrolled(AccountRow account) {
        return "VERIFIED".equals(account.status())
                && account.emailVerifiedAt() != null
                && "ENROLLED".equals(account.mfaState());
    }
}
