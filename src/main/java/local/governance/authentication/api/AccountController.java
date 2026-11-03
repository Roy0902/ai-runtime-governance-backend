package local.governance.authentication.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import local.governance.authentication.api.request.AcknowledgementRequest;
import local.governance.authentication.api.response.AccountResponse;
import local.governance.authentication.api.response.AuthenticatorSetupResponse;
import local.governance.authentication.api.request.CompleteAuthenticatorReplacementRequest;
import local.governance.authentication.api.response.AuthenticationContextResponse;
import local.governance.authentication.api.request.ChangePasswordRequest;
import local.governance.authentication.api.response.SecurityOperationResponse;
import local.governance.authentication.api.response.RecoveryCodeGenerationResponse;
import local.governance.authentication.api.request.StartSecurityOperationRequest;
import local.governance.authentication.application.AuthenticationService;
import local.governance.authentication.application.EnrollmentService;
import local.governance.authentication.session.SessionService;
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

    private final AuthenticationService authentication;
    private final EnrollmentService enrollment;
    private final SessionService sessions;

    public AccountController(AuthenticationService authentication, EnrollmentService enrollment,
            SessionService sessions) {
        this.authentication = authentication;
        this.enrollment = enrollment;
        this.sessions = sessions;
    }

    @GetMapping
    public AccountResponse account(HttpServletRequest request) {
        return authentication.account(request);
    }

    @PostMapping("/password")
    public AuthenticationContextResponse changePassword(@RequestBody ChangePasswordRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        return authentication.changePassword(body, request, response);
    }

    @PostMapping("/authenticator-replacements")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthenticatorSetupResponse startReplacement(@RequestBody StartSecurityOperationRequest body,
            HttpServletRequest request) {
        sessions.requireCsrfIfBrowser(request);
        return enrollment.startReplacement(body, request);
    }

    @PostMapping("/authenticator-replacements/{operationId}/complete")
    public AuthenticationContextResponse completeReplacement(@PathVariable UUID operationId,
            @RequestBody CompleteAuthenticatorReplacementRequest body, HttpServletRequest request, HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        return enrollment.completeReplacement(body, operationId, request, response);
    }

    @PostMapping("/recovery-code-generations")
    @ResponseStatus(HttpStatus.CREATED)
    public RecoveryCodeGenerationResponse startRegeneration(@RequestBody StartSecurityOperationRequest body,
            HttpServletRequest request) {
        sessions.requireCsrfIfBrowser(request);
        return enrollment.startRegeneration(body, request);
    }

    @PostMapping("/recovery-code-generations/{operationId}/complete")
    public AuthenticationContextResponse completeRegeneration(@PathVariable UUID operationId, @RequestBody AcknowledgementRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        return enrollment.completeRegeneration(body, operationId, request, response);
    }

    @GetMapping("/security/operations/{operationId}")
    public SecurityOperationResponse readOperation(@PathVariable UUID operationId, HttpServletRequest request) {
        return enrollment.readOperation(operationId, request);
    }

    @DeleteMapping("/security/operations/{operationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID operationId, HttpServletRequest request) {
        sessions.requireCsrfIfBrowser(request);
        enrollment.cancel(operationId, request);
    }
}
