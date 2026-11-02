package local.governance.auth.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import local.governance.api.ApiDtos.AccountDto;
import local.governance.api.ApiDtos.ContextDto;
import local.governance.api.ApiDtos.PasswordChangeBody;
import local.governance.auth.application.AuthService;
import local.governance.auth.session.SessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    private final AuthService auth;
    private final SessionService sessions;

    public AccountController(AuthService auth, SessionService sessions) {
        this.auth = auth;
        this.sessions = sessions;
    }

    @GetMapping
    public AccountDto account(HttpServletRequest request) {
        return auth.account(request);
    }

    @PostMapping("/password")
    public ContextDto changePassword(@RequestBody PasswordChangeBody body, HttpServletRequest request,
            HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        return auth.changePassword(body, request, response);
    }
}
