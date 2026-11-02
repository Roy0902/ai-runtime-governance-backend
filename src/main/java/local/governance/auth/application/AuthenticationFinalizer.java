package local.governance.auth.application;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.UUID;
import local.governance.api.ApiException;
import local.governance.api.ApiDtos.ContextDto;
import local.governance.api.ApiDtos.Factor;
import local.governance.auth.persistence.AuthRepository;
import local.governance.auth.persistence.AuthRepository.AccountRow;
import local.governance.auth.session.ContextMapper;
import local.governance.auth.session.SessionService;
import local.governance.auth.totp.FactorVerifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationFinalizer {

    private final AuthRepository repository;
    private final FactorVerifier factors;
    private final SessionService sessions;
    private final ContextMapper contexts;
    private final Clock clock;

    public AuthenticationFinalizer(AuthRepository repository, FactorVerifier factors,
            SessionService sessions, ContextMapper contexts, Clock clock) {
        this.repository = repository;
        this.factors = factors;
        this.sessions = sessions;
        this.contexts = contexts;
        this.clock = clock;
    }

    @Transactional
    public ContextDto complete(UUID accountId, long credentialVersion, Factor factor,
            HttpServletRequest request, HttpServletResponse response) {
        AccountRow account = repository.accountByIdForUpdate(accountId)
                .orElseThrow(ApiException::signInFailed);
        if (account.version() != credentialVersion
                || !"VERIFIED".equals(account.status())
                || account.emailVerifiedAt() == null
                || !"ENROLLED".equals(account.mfaState())
                || account.recoveryRequiredAt() != null) {
            throw ApiException.signInFailed();
        }

        factors.verify(account, factor);

        SessionService.Current previous = sessions.read(request).orElse(null);
        var current = previous == null
                ? sessions.create(response, "HUMAN", account.id(), null, clock.instant())
                : sessions.rotate(response, previous, "HUMAN", account.id(), null, clock.instant());
        return contexts.context(current, account);
    }
}
