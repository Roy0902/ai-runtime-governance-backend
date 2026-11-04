package local.governance.guard.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import local.governance.api.APIException;
import local.governance.config.GovernanceProperties;
import local.governance.guard.GuardDecisionCore;
import local.governance.guard.GuardDetectorCatalog;
import local.governance.guard.GuardEffect;
import local.governance.guard.GuardFinding;
import local.governance.guard.GuardResult;
import local.governance.guard.GuardRule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/__test/guard")
@ConditionalOnProperty(name = "governance.test-control.enabled", havingValue = "true")
public class GuardTestController {

    private final GuardDecisionCore core = new GuardDecisionCore();
    private final GuardDetectorCatalog detectorCatalog = new GuardDetectorCatalog(core);
    private final GovernanceProperties properties;

    public GuardTestController(GovernanceProperties properties) {
        this.properties = properties;
    }

    @PostMapping("/evaluate")
    public ResponseEntity<?> evaluate(
            @RequestHeader(value = "X-Test-Token", required = false) String token,
            @RequestBody EvaluateRequest body) {
        check(token);
        List<GuardRule> rules = new ArrayList<>();
        if (body.rules() != null) {
            for (RuleDto rule : body.rules()) {
                rules.add(new GuardRule(
                        rule.id(),
                        rule.enabled() == null || rule.enabled(),
                        effect(rule.effect()),
                        rule.priority() == null ? 0 : rule.priority(),
                        rule.marker()));
            }
        }
        List<GuardFinding> findings = new ArrayList<>();
        if (body.findings() != null) {
            for (FindingDto finding : body.findings()) {
                findings.add(new GuardFinding(finding.ruleId(), finding.start(), finding.end()));
            }
        }

        GuardResult result = core.evaluate(
                body.policyId(), body.policyVersion(), body.message(), rules, findings);
        return response(result);
    }

    @PostMapping("/detect")
    public ResponseEntity<?> detect(
            @RequestHeader(value = "X-Test-Token", required = false) String token,
            @RequestBody DetectRequest body) {
        check(token);
        return response(detectorCatalog.evaluate(body.policyId(), body.policyVersion(), body.message()));
    }

    private static ResponseEntity<?> response(GuardResult result) {
        if (result instanceof GuardResult.GuardFailure failure) {
            return ResponseEntity.status(422).body(Map.of(
                    "code", "GUARD_EVALUATION_FAILED",
                    "reasons", failure.reasons()));
        }
        return ResponseEntity.ok(result);
    }

    private static GuardEffect effect(String value) {
        if (value == null) {
            return null;
        }
        try {
            return GuardEffect.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void check(String token) {
        if (!properties.getTestControl().isEnabled()
                || token == null
                || !token.equals(properties.getTestControl().getToken())) {
            throw new APIException("FORBIDDEN", "Test control is not available.");
        }
    }

    public record EvaluateRequest(
            String policyId,
            String policyVersion,
            String message,
            List<RuleDto> rules,
            List<FindingDto> findings) {}

    public record DetectRequest(
            String policyId,
            String policyVersion,
            String message) {}

    public record RuleDto(
            String id,
            Boolean enabled,
            String effect,
            Integer priority,
            String marker) {}

    public record FindingDto(
            String ruleId,
            Integer start,
            Integer end) {}
}
