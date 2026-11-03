package local.governance.authentication.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import local.governance.authentication.api.response.AuthenticatorSetupResponse;
import local.governance.authentication.api.request.CompleteEnrollmentRequest;
import local.governance.authentication.api.response.AuthenticationContextResponse;
import local.governance.authentication.api.request.SetupAccessRequest;
import local.governance.authentication.api.request.EmailRequest;
import local.governance.authentication.api.response.AcceptedMailResponse;
import local.governance.authentication.api.response.SecurityOperationResponse;
import local.governance.authentication.api.request.PasswordResetRequest;
import local.governance.authentication.api.request.ReauthenticationRequest;
import local.governance.authentication.api.request.RegistrationRequest;
import local.governance.authentication.api.request.RestartEnrollmentRequest;
import local.governance.authentication.api.request.SignInRequest;
import local.governance.authentication.api.request.StartSecurityOperationRequest;
import local.governance.authentication.api.request.VerificationTokenRequest;
import local.governance.authentication.application.AuthenticationService;
import local.governance.authentication.application.EnrollmentService;
import local.governance.authentication.session.SessionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthenticationController {

    private final AuthenticationService authentication;
    private final EnrollmentService enrollment;
    private final SessionService sessions;

    public AuthenticationController(AuthenticationService authentication, EnrollmentService enrollment,
            SessionService sessions) {
        this.authentication = authentication;
        this.enrollment = enrollment;
        this.sessions = sessions;
    }

    @GetMapping("/context")
    public AuthenticationContextResponse context(HttpServletRequest request, HttpServletResponse response) {
        return authentication.context(request, response);
    }

    @PostMapping("/registrations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AcceptedMailResponse register(@RequestBody RegistrationRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        return authentication.register(body, request, response);
    }

    @PostMapping("/email-verifications/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AcceptedMailResponse resend(@RequestBody EmailRequest body, HttpServletRequest request) {
        sessions.requireCsrfIfBrowser(request);
        return authentication.resend(body, request);
    }

    @PostMapping("/email-verifications/consume")
    public AuthenticationContextResponse verify(@RequestBody VerificationTokenRequest body, HttpServletRequest request, HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        return authentication.verify(body, request, response);
    }

    @PostMapping("/password-resets")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AcceptedMailResponse requestReset(@RequestBody EmailRequest body, HttpServletRequest request) {
        sessions.requireCsrfIfBrowser(request);
        return authentication.requestReset(body, request);
    }

    @PostMapping("/password-resets/consume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void consumeReset(@RequestBody PasswordResetRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        authentication.consumeReset(body, response);
    }

    @PostMapping("/setup-access")
    public AuthenticationContextResponse setupAccess(@RequestBody SetupAccessRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        return authentication.setupAccess(body, request, response);
    }

    @PostMapping("/sign-in")
    public AuthenticationContextResponse signIn(@RequestBody SignInRequest body, HttpServletRequest request, HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        return authentication.signIn(body, request, response);
    }

    @PostMapping("/sign-out")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void signOut(HttpServletRequest request, HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        authentication.signOut(request, response);
    }

    @PostMapping("/reauthentication")
    public AuthenticationContextResponse reauthenticate(@RequestBody ReauthenticationRequest body, HttpServletRequest request) {
        sessions.requireCsrfIfBrowser(request);
        return authentication.reauthenticate(body, request);
    }

    @GetMapping("/enrollment")
    public SecurityOperationResponse readEnrollment(HttpServletRequest request) {
        return enrollment.readEnrollment(request);
    }

    @PostMapping("/enrollment")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthenticatorSetupResponse startEnrollment(@RequestBody StartSecurityOperationRequest body, HttpServletRequest request) {
        sessions.requireCsrfIfBrowser(request);
        return enrollment.start(body, request);
    }

    @PostMapping("/enrollment/restart")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthenticatorSetupResponse restartEnrollment(@RequestBody RestartEnrollmentRequest body,
            HttpServletRequest request) {
        sessions.requireCsrfIfBrowser(request);
        return enrollment.restart(body, request);
    }

    @PostMapping("/enrollment/complete")
    public AuthenticationContextResponse completeEnrollment(@RequestBody CompleteEnrollmentRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        return enrollment.complete(body, request, response);
    }
}
