package local.governance.auth.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import local.governance.api.ApiDtos.AcknowledgementBody;
import local.governance.api.ApiDtos.AuthenticatorSecretsDto;
import local.governance.api.ApiDtos.CompleteEnrollmentBody;
import local.governance.api.ApiDtos.CompleteReplacementBody;
import local.governance.api.ApiDtos.ContextDto;
import local.governance.api.ApiDtos.OperationDto;
import local.governance.api.ApiDtos.RecoverySecretsDto;
import local.governance.api.ApiDtos.RestartEnrollmentBody;
import local.governance.api.ApiDtos.StartOperationBody;
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
@RequestMapping("/api/v1")
public class EnrollmentController {

    private final EnrollmentService enrollment;
    private final SessionService sessions;

    public EnrollmentController(EnrollmentService enrollment, SessionService sessions) {
        this.enrollment = enrollment;
        this.sessions = sessions;
    }

    @GetMapping("/auth/enrollment")
    public OperationDto readEnrollment(HttpServletRequest request) {
        return enrollment.readEnrollment(request);
    }

    @PostMapping("/auth/enrollment")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthenticatorSecretsDto start(@RequestBody StartOperationBody body, HttpServletRequest request,
            HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        return enrollment.start(body, request, response);
    }

    @PostMapping("/auth/enrollment/restart")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthenticatorSecretsDto restart(@RequestBody RestartEnrollmentBody body, HttpServletRequest request,
            HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        return enrollment.restart(body, request, response);
    }

    @PostMapping("/auth/enrollment/complete")
    public ContextDto complete(@RequestBody CompleteEnrollmentBody body, HttpServletRequest request,
            HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        return enrollment.complete(body, request, response);
    }

    @PostMapping("/account/authenticator-replacements")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthenticatorSecretsDto startReplacement(@RequestBody StartOperationBody body, HttpServletRequest request,
            HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        return enrollment.startReplacement(body, request, response);
    }

    @PostMapping("/account/authenticator-replacements/{operationId}/complete")
    public ContextDto completeReplacement(@PathVariable UUID operationId, @RequestBody CompleteReplacementBody body,
            HttpServletRequest request, HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        return enrollment.completeReplacement(body, operationId, request, response);
    }

    @PostMapping("/account/recovery-code-generations")
    @ResponseStatus(HttpStatus.CREATED)
    public RecoverySecretsDto startRegeneration(@RequestBody StartOperationBody body, HttpServletRequest request,
            HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        return enrollment.startRegeneration(body, request, response);
    }

    @PostMapping("/account/recovery-code-generations/{operationId}/complete")
    public ContextDto completeRegeneration(@PathVariable UUID operationId, @RequestBody AcknowledgementBody body,
            HttpServletRequest request, HttpServletResponse response) {
        sessions.requireCsrfIfBrowser(request);
        return enrollment.completeRegeneration(body, operationId, request, response);
    }

    @GetMapping("/account/security/operations/{operationId}")
    public OperationDto readOperation(@PathVariable UUID operationId, HttpServletRequest request) {
        return enrollment.readOperation(operationId, request);
    }

    @DeleteMapping("/account/security/operations/{operationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID operationId, HttpServletRequest request) {
        sessions.requireCsrfIfBrowser(request);
        enrollment.cancel(operationId, request);
    }
}
