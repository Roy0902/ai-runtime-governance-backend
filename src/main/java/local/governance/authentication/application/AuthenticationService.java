package local.governance.authentication.application;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import local.governance.authentication.api.response.AuthenticationContextResponse;
import local.governance.authentication.api.request.SetupAccessRequest;
import local.governance.authentication.api.request.EmailRequest;
import local.governance.authentication.api.response.AcceptedMailResponse;
import local.governance.authentication.api.request.PasswordResetRequest;
import local.governance.authentication.api.request.RegistrationRequest;
import local.governance.authentication.api.request.SignInRequest;
import local.governance.authentication.api.request.VerificationTokenRequest;
import local.governance.api.APIException;
import local.governance.authentication.crypto.HashWorkLimiter;
import local.governance.authentication.crypto.PasswordHashService;
import local.governance.authentication.crypto.SecureTokenService;
import local.governance.authentication.domain.EmailPolicy;
import local.governance.authentication.domain.PasswordPolicy;
import local.governance.authentication.mail.MailService;
import local.governance.authentication.persistence.AuthenticationRepository;
import local.governance.authentication.persistence.AuthenticationRepository.AccountRow;
import local.governance.authentication.persistence.AuthenticationRepository.LinkRow;
import local.governance.authentication.ratelimit.RateLimitService;
import local.governance.authentication.session.ContextMapper;
import local.governance.authentication.session.SessionService;
import local.governance.authentication.totp.FactorVerifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationService {

    public static final String ACCEPTED =
            "If this request is eligible, we'll send an email with the next steps.";

    private final AuthenticationRepository repository;
    private final PasswordHashService passwords;
    private final HashWorkLimiter hashLimiter;
    private final SecureTokenService tokens;
    private final MailService mailService;
    private final SessionService sessions;
    private final AuthenticationFinalizer authenticationFinalizer;
    private final RateLimitService rateLimit;
    private final FactorVerifier factors;
    private final ContextMapper contexts;
    private final Clock clock;
    private final local.governance.config.TestFaultRegistry faults;

    public AuthenticationService(
            AuthenticationRepository repository,
            PasswordHashService passwords,
            HashWorkLimiter hashLimiter,
            SecureTokenService tokens,
            MailService mailService,
            SessionService sessions,
            AuthenticationFinalizer authenticationFinalizer,
            RateLimitService rateLimit,
            FactorVerifier factors,
            ContextMapper contexts,
            Clock clock,
            local.governance.config.TestFaultRegistry faults) {
        this.repository = repository;
        this.passwords = passwords;
        this.hashLimiter = hashLimiter;
        this.tokens = tokens;
        this.mailService = mailService;
        this.sessions = sessions;
        this.authenticationFinalizer = authenticationFinalizer;
        this.rateLimit = rateLimit;
        this.factors = factors;
        this.contexts = contexts;
        this.clock = clock;
        this.faults = faults;
    }

    public AuthenticationContextResponse context(HttpServletRequest request, HttpServletResponse response) {
        AccountRow account = null;
        SessionService.Current current = sessions.read(request).orElse(null);
        if (current != null && current.accountId() != null) {
            account = repository.accountById(current.accountId()).orElse(null);
            if (account == null || "DISABLED".equals(account.status())) {
                sessions.end(response, current);
                current = null;
                account = null;
            }
        }
        if (current == null) {
            current = sessions.anonymous(request, response);
        }
        return contexts.context(current, account);
    }

    @Transactional
    public AcceptedMailResponse register(RegistrationRequest body, HttpServletRequest request, HttpServletResponse response) {
        String email = EmailPolicy.normalize(body.email());
        PasswordPolicy.validateNew(body.password(), "password");
        rateLimit.admit(RateLimitService.SIGNUP_SOURCE, rateLimit.sourceKey(request), Duration.ofHours(1), 10, 3600);
        rateLimit.checkMailIdentity(email);

        if (repository.accountByEmail(email).isPresent()) {
            return new AcceptedMailResponse(ACCEPTED);
        }
        String hash = hashLimiter.submit(() -> passwords.hash(body.password()));
        UUID accountId = UUID.randomUUID();
        repository.insertAccount(accountId, email, hash, clock.instant());
        mailService.issueVerification(accountId, email);
        return new AcceptedMailResponse(ACCEPTED);
    }

    @Transactional
    public AuthenticationContextResponse verify(VerificationTokenRequest body, HttpServletRequest request, HttpServletResponse response) {
        String token = body.token();
        if (token == null || token.isBlank()) {
            throw APIException.notFound();
        }
        Instant now = clock.instant();
        LinkRow link = repository.lockLinkByToken(tokens.hash(token)).orElseThrow(
                () -> new APIException("LINK_NOT_USABLE", "This link can't be used. Request a new email."));
        if (!"VERIFY".equals(link.purpose()) || link.consumedAt() != null || link.supersededAt() != null
                || !now.isBefore(link.expiresAt()) || !repository.consumeLink(link.id(), now)) {
            throw new APIException("LINK_NOT_USABLE", "This link can't be used. Request a new email.");
        }
        if (faults.enabled("consume-rollback")) {
            throw APIException.unavailable();
        }
        AccountRow account = repository.accountById(link.accountId()).orElseThrow(APIException::notFound);
        if (account.emailVerifiedAt() == null) {
            repository.markVerified(account.id(), now);
        }
        if ("NONE".equals(account.mfaState())) {
            repository.setMfaState(account.id(), "PENDING", now);
        }
        SessionService.Current before = sessions.read(request).orElse(null);
        SessionService.Current current = before == null
                ? sessions.create(response, "SETUP", account.id(), "INITIAL_ENROLLMENT", null)
                : sessions.rotate(response, before, "SETUP", account.id(), "INITIAL_ENROLLMENT", null);
        AccountRow updated = repository.accountById(account.id()).orElse(account);
        return contexts.context(current, updated);
    }

    @Transactional
    public AcceptedMailResponse resend(EmailRequest body, HttpServletRequest request) {
        String email = EmailPolicy.normalize(body.email());
        if (faults.enabled("mail-admission")) {
            throw APIException.unavailable();
        }
        rateLimit.admit(RateLimitService.MAIL_SOURCE, rateLimit.sourceKey(request), Duration.ofHours(1), 20, 3600);
        rateLimit.checkMailIdentity(email);
        repository.accountByEmail(email)
                .filter((account) -> "PENDING".equals(account.status()) && account.emailVerifiedAt() == null)
                .ifPresent((account) -> mailService.issueVerification(account.id(), account.email()));
        return new AcceptedMailResponse(ACCEPTED);
    }

    @Transactional
    public AcceptedMailResponse requestReset(EmailRequest body, HttpServletRequest request) {
        String email = EmailPolicy.normalize(body.email());
        if (faults.enabled("mail-admission")) {
            throw APIException.unavailable();
        }
        rateLimit.admit(RateLimitService.MAIL_SOURCE, rateLimit.sourceKey(request), Duration.ofHours(1), 20, 3600);
        rateLimit.checkMailIdentity(email);
        repository.accountByEmail(email)
                .filter((account) -> account.emailVerifiedAt() != null && !"DISABLED".equals(account.status()))
                .ifPresent((account) -> mailService.issueReset(account.id(), account.email()));
        return new AcceptedMailResponse(ACCEPTED);
    }

    @Transactional
    public void consumeReset(PasswordResetRequest body, HttpServletResponse response) {
        PasswordPolicy.validateNew(body.newPassword(), "newPassword");
        if (body.token() == null || body.token().isBlank()) {
            throw new APIException("LINK_NOT_USABLE", "This link can't be used. Request a new email.");
        }
        Instant now = clock.instant();
        LinkRow link = repository.lockLinkByToken(tokens.hash(body.token())).orElseThrow(
                () -> new APIException("LINK_NOT_USABLE", "This link can't be used. Request a new email."));
        if (!"RESET".equals(link.purpose()) || link.consumedAt() != null || link.supersededAt() != null
                || !now.isBefore(link.expiresAt())) {
            throw new APIException("LINK_NOT_USABLE", "This link can't be used. Request a new email.");
        }
        String hash = hashLimiter.submit(() -> passwords.hash(body.newPassword()));
        if (!repository.consumeLink(link.id(), now)) {
            throw new APIException("LINK_NOT_USABLE", "This link can't be used. Request a new email.");
        }
        repository.updatePassword(link.accountId(), hash, now);
        repository.revokeAllSessions(link.accountId(), now);
        sessions.clearCookie(response);
    }

    public AuthenticationContextResponse setupAccess(SetupAccessRequest body, HttpServletRequest request, HttpServletResponse response) {
        String email = EmailPolicy.normalize(body.email());
        rateLimit.admit(RateLimitService.AUTH_SOURCE, rateLimit.sourceKey(request), Duration.ofSeconds(60), 30, 60);
        rateLimit.checkCooldown(email);
        AccountRow account = repository.accountByEmail(email)
                .filter((candidate) -> candidate.emailVerifiedAt() != null && !"DISABLED".equals(candidate.status()))
                .orElseThrow(() -> {
                    rateLimit.recordFailure(email);
                    return new APIException("SETUP_ACCESS_FAILED", "Sign-in failed. Check your details and try again.");
                });
        PasswordHashService.Verification verification = hashLimiter.submit(
                () -> passwords.verify(body.password() == null ? "" : body.password(), account.passwordHash()));
        if (verification == PasswordHashService.Verification.FAILED) {
            rateLimit.recordFailure(email);
            throw new APIException("SETUP_ACCESS_FAILED", "Sign-in failed. Check your details and try again.");
        }
        if ("ENROLLED".equals(account.mfaState()) && account.recoveryRequiredAt() == null) {
            rateLimit.recordFailure(email);
            throw new APIException("SETUP_ACCESS_FAILED", "Sign-in failed. Check your details and try again.");
        }
        rateLimit.clearFailures(email);
        String purpose = account.recoveryRequiredAt() == null ? "INITIAL_ENROLLMENT" : "RECOVERY_ENROLLMENT";
        SessionService.Current before = sessions.read(request).orElse(null);
        SessionService.Current current = before == null
                ? sessions.create(response, "SETUP", account.id(), purpose, null)
                : sessions.rotate(response, before, "SETUP", account.id(), purpose, null);
        return contexts.context(current, account);
    }

    public AuthenticationContextResponse signIn(SignInRequest body, HttpServletRequest request, HttpServletResponse response) {
        String email = EmailPolicy.normalize(body.email());
        rateLimit.admit(RateLimitService.AUTH_SOURCE, rateLimit.sourceKey(request), Duration.ofSeconds(60), 30, 60);
        rateLimit.checkCooldown(email);
        AccountRow account = repository.accountByEmail(email).orElse(null);
        if (account == null || "DISABLED".equals(account.status()) || account.emailVerifiedAt() == null) {
            throw credentialFailure(email);
        }
        PasswordHashService.Verification verification = hashLimiter.submit(
                () -> passwords.verify(body.password() == null ? "" : body.password(), account.passwordHash()));
        if (verification == PasswordHashService.Verification.FAILED) {
            throw credentialFailure(email);
        }
        if (!"VERIFIED".equals(account.status()) || !"ENROLLED".equals(account.mfaState())
                || account.recoveryRequiredAt() != null) {
            throw credentialFailure(email);
        }
        long sessionVersion = account.version();
        if (verification == PasswordHashService.Verification.PREDECESSOR) {
            String upgraded = hashLimiter.submit(() -> passwords.hash(body.password()));
            faults.pauseIfEnabled("predecessor-rehash-after-derive");
            if (repository.updatePasswordIfVersion(account.id(), upgraded, account.version(), clock.instant()) != 1) {
                throw credentialFailure(email);
            }
            sessionVersion++;
        }
        rateLimit.clearFailures(email);
        try {
            return authenticationFinalizer.complete(account.id(), sessionVersion, body.secondFactor(),
                    request, response);
        } catch (APIException exception) {
            if ("SIGN_IN_FAILED".equals(exception.code())) {
                throw credentialFailure(email);
            }
            throw exception;
        }
    }

    public void signOut(HttpServletRequest request, HttpServletResponse response) {
        sessions.read(request).ifPresent((current) -> sessions.end(response, current));
        sessions.clearCookie(response);
    }

    public AuthenticationContextResponse reauthenticate(local.governance.authentication.api.request.ReauthenticationRequest body, HttpServletRequest request) {
        SessionService.Current current = sessions.requireHuman(request);
        AccountRow account = repository.accountById(current.accountId()).orElseThrow(APIException::signInRequired);
        PasswordHashService.Verification verification = hashLimiter.submit(
                () -> passwords.verify(body.password() == null ? "" : body.password(), account.passwordHash()));
        if (verification == PasswordHashService.Verification.FAILED) {
            throw APIException.signInFailed();
        }
        try {
            factors.verify(account, body.secondFactor());
        } catch (APIException exception) {
            throw exception;
        }
        sessions.setRecentAuth(current);
        SessionService.Current refreshed = sessions.requireHuman(request);
        return contexts.context(refreshed, account);
    }

    public local.governance.authentication.api.response.AccountResponse account(HttpServletRequest request) {
        SessionService.Current current = sessions.requireHuman(request);
        AccountRow account = repository.accountById(current.accountId()).orElseThrow(APIException::signInRequired);
        sessions.touch(current);
        return contexts.account(account, current);
    }

    private APIException credentialFailure(String email) {
        RateLimitService.FailureOutcome outcome = rateLimit.recordFailure(email);
        if (outcome.count() == 5 || outcome.count() == 10 || outcome.count() == 20) {
            return APIException.rateLimited(outcome.delaySeconds());
        }
        return APIException.signInFailed();
    }

    @Transactional
    public AuthenticationContextResponse changePassword(local.governance.authentication.api.request.ChangePasswordRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        SessionService.Current current = sessions.requireHuman(request);
        sessions.requireRecentAuth(current);
        PasswordPolicy.validateNew(body.newPassword(), "newPassword");
        AccountRow account = repository.accountById(current.accountId()).orElseThrow(APIException::signInRequired);
        String hash = hashLimiter.submit(() -> passwords.hash(body.newPassword()));
        Instant now = clock.instant();
        repository.updatePassword(account.id(), hash, now);
        repository.revokeOtherSessions(account.id(), current.sessionId(), now);
        SessionService.Current rotated = sessions.rotateForSettings(response, current);
        AccountRow updated = repository.accountById(account.id()).orElse(account);
        return contexts.context(rotated, updated);
    }
}
