package local.governance.auth.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import local.governance.api.ApiDtos.ContextDto;
import local.governance.api.ApiDtos.CredentialsBody;
import local.governance.api.ApiDtos.EmailBody;
import local.governance.api.ApiDtos.MailAcceptedDto;
import local.governance.api.ApiDtos.PasswordResetBody;
import local.governance.api.ApiDtos.ReauthenticationBody;
import local.governance.api.ApiDtos.RegistrationBody;
import local.governance.api.ApiDtos.SignInBody;
import local.governance.api.ApiDtos.TokenBody;
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
    public ContextDto context(HttpServletRequest request, HttpServletResponse response) {
        return auth.context(request, response);
    }

    @PostMapping("/registrations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MailAcceptedDto register(@RequestBody RegistrationBody body, HttpServletRequest request,
            HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        return auth.register(body, request, response);
    }

    @PostMapping("/email-verifications/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MailAcceptedDto resend(@RequestBody EmailBody body, HttpServletRequest request) {
        sessions.requireCsrfIfBrowser(request);
        return auth.resend(body, request);
    }

    @PostMapping("/email-verifications/consume")
    public ContextDto verify(@RequestBody TokenBody body, HttpServletRequest request, HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        return auth.verify(body, request, response);
    }

    @PostMapping("/password-resets")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MailAcceptedDto requestReset(@RequestBody EmailBody body, HttpServletRequest request) {
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
    public ContextDto setupAccess(@RequestBody CredentialsBody body, HttpServletRequest request,
            HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        return auth.setupAccess(body, request, response);
    }

    @PostMapping("/sign-in")
    public ContextDto signIn(@RequestBody SignInBody body, HttpServletRequest request, HttpServletResponse response) {
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
    public ContextDto reauthenticate(@RequestBody ReauthenticationBody body, HttpServletRequest request) {
        sessions.requireCsrfIfBrowser(request);
        return auth.reauthenticate(body, request);
    }
}
