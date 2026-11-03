package local.governance.auth.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import local.governance.api.AcknowledgementBody;
import local.governance.api.AccountDTO;
import local.governance.api.AuthenticatorSecretsDTO;
import local.governance.api.CompleteReplacementBody;
import local.governance.api.ContextDTO;
import local.governance.api.PasswordChangeBody;
import local.governance.api.OperationDTO;
import local.governance.api.RecoverySecretsDTO;
import local.governance.api.StartOperationBody;
import local.governance.auth.application.AuthService;
import local.governance.auth.application.EnrollmentService;
import local.governance.auth.session.SessionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    private final AuthService auth;
    private final EnrollmentService enrollment;
    private final SessionService sessions;

    public AccountController(AuthService auth, EnrollmentService enrollment, SessionService sessions) {
        this.auth = auth;
        this.enrollment = enrollment;
        this.sessions = sessions;
    }

    @GetMapping
    public AccountDTO account(HttpServletRequest request) {
        return auth.account(request);
    }

    @PostMapping("/password")
    public ContextDTO changePassword(@RequestBody PasswordChangeBody body, HttpServletRequest request,
            HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        return auth.changePassword(body, request, response);
    }

    @PostMapping("/authenticator-replacements")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthenticatorSecretsDTO startReplacement(@RequestBody StartOperationBody body,
            HttpServletRequest request) {
        sessions.requireCsrfIfBrowser(request);
        return enrollment.startReplacement(body, request);
    }

    @PostMapping("/authenticator-replacements/{operationId}/complete")
    public ContextDTO completeReplacement(@PathVariable UUID operationId,
            @RequestBody CompleteReplacementBody body, HttpServletRequest request, HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        return enrollment.completeReplacement(body, operationId, request, response);
    }

    @PostMapping("/recovery-code-generations")
    @ResponseStatus(HttpStatus.CREATED)
    public RecoverySecretsDTO startRegeneration(@RequestBody StartOperationBody body,
            HttpServletRequest request) {
        sessions.requireCsrfIfBrowser(request);
        return enrollment.startRegeneration(body, request);
    }

    @PostMapping("/recovery-code-generations/{operationId}/complete")
    public ContextDTO completeRegeneration(@PathVariable UUID operationId, @RequestBody AcknowledgementBody body,
            HttpServletRequest request, HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        return enrollment.completeRegeneration(body, operationId, request, response);
    }

    @GetMapping("/security/operations/{operationId}")
    public OperationDTO readOperation(@PathVariable UUID operationId, HttpServletRequest request) {
        return enrollment.readOperation(operationId, request);
    }

    @DeleteMapping("/security/operations/{operationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID operationId, HttpServletRequest request) {
        sessions.requireCsrfIfBrowser(request);
        enrollment.cancel(operationId, request);
    }
}
