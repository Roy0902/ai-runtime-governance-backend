package local.governance.authentication.application;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.UUID;
import local.governance.api.APIException;
import local.governance.authentication.api.response.AuthenticationContextResponse;
import local.governance.authentication.api.request.AuthenticationFactor;
import local.governance.authentication.persistence.AuthenticationRepository;
import local.governance.authentication.persistence.AuthenticationRepository.AccountRow;
import local.governance.authentication.session.ContextMapper;
import local.governance.authentication.session.SessionService;
import local.governance.authentication.totp.FactorVerifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationFinalizer {

    private final AuthenticationRepository repository;
    private final FactorVerifier factors;
    private final SessionService sessions;
    private final ContextMapper contexts;
    private final Clock clock;

    public AuthenticationFinalizer(AuthenticationRepository repository, FactorVerifier factors,
            SessionService sessions, ContextMapper contexts, Clock clock) {
        this.repository = repository;
        this.factors = factors;
        this.sessions = sessions;
        this.contexts = contexts;
        this.clock = clock;
    }

    @Transactional
    public AuthenticationContextResponse complete(UUID accountId, long credentialVersion, AuthenticationFactor factor,
            HttpServletRequest request, HttpServletResponse response) {
        AccountRow account = repository.accountByIdForUpdate(accountId)
                .orElseThrow(APIException::signInFailed);
        if (account.version() != credentialVersion
                || !"VERIFIED".equals(account.status())
                || account.emailVerifiedAt() == null
                || !"ENROLLED".equals(account.mfaState())
                || account.recoveryRequiredAt() != null) {
            throw APIException.signInFailed();
        }

        factors.verify(account, factor);

        SessionService.Current previous = sessions.read(request).orElse(null);
        var current = previous == null
                ? sessions.create(response, "HUMAN", account.id(), null, clock.instant())
                : sessions.rotate(response, previous, "HUMAN", account.id(), null, clock.instant());
        return contexts.context(current, account);
    }
}
