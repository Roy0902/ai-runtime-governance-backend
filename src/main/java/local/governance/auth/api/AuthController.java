package local.governance.auth.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import local.governance.api.ContextDTO;
import local.governance.api.CredentialsBody;
import local.governance.api.EmailBody;
import local.governance.api.MailAcceptedDTO;
import local.governance.api.PasswordResetBody;
import local.governance.api.ReauthenticationBody;
import local.governance.api.RegistrationBody;
import local.governance.api.SignInBody;
import local.governance.api.TokenBody;
import local.governance.auth.application.AuthService;
import local.governance.auth.session.SessionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;
    private final SessionService sessions;

    public AuthController(AuthService auth, SessionService sessions) {
        this.auth = auth;
        this.sessions = sessions;
    }

    @GetMapping("/context")
    public ContextDTO context(HttpServletRequest request, HttpServletResponse response) {
        return auth.context(request, response);
    }

    @PostMapping("/registrations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MailAcceptedDTO register(@RequestBody RegistrationBody body, HttpServletRequest request,
            HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        return auth.register(body, request, response);
    }

    @PostMapping("/email-verifications/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MailAcceptedDTO resend(@RequestBody EmailBody body, HttpServletRequest request) {
        sessions.requireCsrfIfBrowser(request);
        return auth.resend(body, request);
    }

    @PostMapping("/email-verifications/consume")
    public ContextDTO verify(@RequestBody TokenBody body, HttpServletRequest request, HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        return auth.verify(body, request, response);
    }

    @PostMapping("/password-resets")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MailAcceptedDTO requestReset(@RequestBody EmailBody body, HttpServletRequest request) {
        sessions.requireCsrfIfBrowser(request);
        return auth.requestReset(body, request);
    }

    @PostMapping("/password-resets/consume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void consumeReset(@RequestBody PasswordResetBody body, HttpServletRequest request,
            HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        auth.consumeReset(body, response);
    }

    @PostMapping("/setup-access")
    public ContextDTO setupAccess(@RequestBody CredentialsBody body, HttpServletRequest request,
            HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        return auth.setupAccess(body, request, response);
    }

    @PostMapping("/sign-in")
    public ContextDTO signIn(@RequestBody SignInBody body, HttpServletRequest request, HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        return auth.signIn(body, request, response);
    }

    @PostMapping("/sign-out")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void signOut(HttpServletRequest request, HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        auth.signOut(request, response);
    }

    @PostMapping("/reauthentication")
    public ContextDTO reauthenticate(@RequestBody ReauthenticationBody body, HttpServletRequest request) {
        sessions.requireCsrfIfBrowser(request);
        return auth.reauthenticate(body, request);
    }
}
