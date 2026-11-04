package local.governance.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GuardDetectorCatalogTest {

    private final GuardDetectorCatalog catalog = new GuardDetectorCatalog(new GuardDecisionCore());

    @Test
    void recognizesTheBuiltInApiTokenFamilies() {
        List<String> tokens = List.of(
                "AKIA1234567890ABCDEF",
                "ghp_abcdefghijklmnopqrstuvwxyz",
                "github_pat_abcdefghijklmnopqrstuvwxyz123456",
                "xoxb-abcdefghijklmnopqrstuvwx",
                "sk-ant-abcdefghijklmnopqrstuvwxyz");

        for (String token : tokens) {
            GuardResult.GuardDecision decision = assertInstanceOf(
                    GuardResult.GuardDecision.class,
                    catalog.evaluate("policy", "v1", token));
            assertEquals(GuardOutcome.REDACT, decision.outcome());
            assertEquals("[REDACTED_API_TOKEN]", decision.message());
            assertEquals(List.of("FX-API-TOKEN"), decision.matchedRuleIds());
        }
    }

    @Test
    void failsClosedWhenDetectionExceedsTheFindingLimit() {
        String message = String.join(" ", java.util.stream.IntStream.range(0, 1_025)
                .mapToObj(index -> "user" + index + "@example.test")
                .toList());

        GuardResult.GuardFailure failure = assertInstanceOf(
                GuardResult.GuardFailure.class,
                catalog.evaluate("policy", "v1", message));
        assertTrue(failure.reasons().contains("FINDING_LIMIT_EXCEEDED"));
    }

    @Test
    void rejectsAnIbanCandidateWithAnInvalidChecksum() {
        GuardResult.GuardDecision decision = assertInstanceOf(
                GuardResult.GuardDecision.class,
                catalog.evaluate("policy", "v1", "GB82 WEST 1234 5698 7654 33"));

        assertEquals(GuardOutcome.ALLOW, decision.outcome());
        assertEquals("GB82 WEST 1234 5698 7654 33", decision.message());
        assertEquals(List.of(), decision.matchedRuleIds());
    }
}
