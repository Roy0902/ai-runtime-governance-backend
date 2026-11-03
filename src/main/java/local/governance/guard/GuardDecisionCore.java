package local.governance.guard;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure guard decision core: compose trusted findings against a trusted catalog.
 * It never detects content, grants approval, persists or dispatches.
 */
public final class GuardDecisionCore {

    public static final int MAX_MESSAGE_BYTES = 65_536;
    public static final int MAX_FINDINGS = 1_024;

    private static final Set<String> MARKERS = Set.of(
            "[REDACTED_CREDENTIAL]",
            "[REDACTED_API_TOKEN]",
            "[REDACTED_EMAIL]",
            "[REDACTED_PHONE]",
            "[REDACTED_PAYMENT_CARD]",
            "[REDACTED_FINANCIAL_ID]");

    public GuardResult evaluate(
            String policyId,
            String policyVersion,
            String message,
            List<GuardRule> rules,
            List<GuardFinding> findings) {
        List<String> reasons = new ArrayList<>();
        if (policyId == null || policyId.isBlank()
                || policyVersion == null || policyVersion.isBlank()) {
            reasons.add("INVALID_POLICY");
        }
        if (message == null) {
            reasons.add("INVALID_TEXT");
            return failure(reasons);
        }
        if (hasUnpairedSurrogate(message)) {
            reasons.add("INVALID_TEXT");
        } else if (message.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            reasons.add("MESSAGE_LIMIT_EXCEEDED");
        }

        List<GuardFinding> safeFindings = findings == null ? List.of() : findings;
        if (safeFindings.size() > MAX_FINDINGS) {
            reasons.add("FINDING_LIMIT_EXCEEDED");
        }

        Map<String, GuardRule> catalog = new LinkedHashMap<>();
        if (rules == null) {
            reasons.add("INVALID_POLICY");
        } else {
            for (GuardRule rule : rules) {
                if (rule == null || rule.id() == null || rule.id().isBlank() || rule.effect() == null) {
                    reasons.add("INVALID_POLICY");
                    continue;
                }
                if (catalog.putIfAbsent(rule.id(), rule) != null) {
                    reasons.add("INVALID_POLICY");
                }
                if (rule.effect() == GuardEffect.REDACT
                        && (rule.priority() < 0 || rule.marker() == null || !MARKERS.contains(rule.marker()))) {
                    reasons.add("INVALID_POLICY");
                }
            }
        }
        if (!reasons.isEmpty()) {
            return failure(reasons);
        }

        int codePoints = message.codePointCount(0, message.length());
        List<Resolved> resolved = new ArrayList<>();
        Set<String> matched = new TreeSet<>();
        boolean deny = false;
        boolean approval = false;

        for (GuardFinding finding : safeFindings) {
            if (finding == null || finding.ruleId() == null) {
                reasons.add("INVALID_FINDING");
                continue;
            }
            GuardRule rule = catalog.get(finding.ruleId());
            if (rule == null) {
                reasons.add("UNKNOWN_RULE");
                continue;
            }
            if (!rule.enabled()) {
                reasons.add("DISABLED_RULE");
                continue;
            }
            matched.add(rule.id());
            if (rule.effect() == GuardEffect.DENY) {
                deny = true;
                if (finding.start() != null || finding.end() != null) {
                    reasons.add("INVALID_FINDING");
                }
                continue;
            }
            if (rule.effect() == GuardEffect.REQUIRE_APPROVAL) {
                approval = true;
                if (finding.start() != null || finding.end() != null) {
                    reasons.add("INVALID_FINDING");
                }
                continue;
            }
            if (finding.start() == null || finding.end() == null
                    || finding.start() < 0
                    || finding.end() <= finding.start()
                    || finding.end() > codePoints) {
                reasons.add("INVALID_FINDING");
                continue;
            }
            resolved.add(new Resolved(finding.start(), finding.end(), rule));
        }
        if (!reasons.isEmpty()) {
            return failure(reasons);
        }

        resolved.sort(Comparator.comparingInt(Resolved::start).thenComparingInt(Resolved::end));
        List<Group> groups = new ArrayList<>();
        int index = 0;
        while (index < resolved.size()) {
            Resolved first = resolved.get(index);
            int start = first.start();
            int end = first.end();
            int topPriority = first.rule().priority();
            GuardRule winner = first.rule();
            boolean tie = false;
            int next = index + 1;
            while (next < resolved.size() && resolved.get(next).start() < end) {
                Resolved candidate = resolved.get(next);
                end = Math.max(end, candidate.end());
                if (candidate.rule().priority() > topPriority) {
                    topPriority = candidate.rule().priority();
                    winner = candidate.rule();
                    tie = false;
                } else if (candidate.rule().priority() == topPriority) {
                    tie = true;
                }
                next += 1;
            }
            if (tie) {
                reasons.add("OVERLAP_PRIORITY_TIE");
            }
            groups.add(new Group(start, end, winner));
            index = next;
        }
        if (!reasons.isEmpty()) {
            return failure(reasons);
        }

        int[] codePointsArray = message.codePoints().toArray();
        StringBuilder transformed = new StringBuilder();
        int cursor = 0;
        for (Group group : groups) {
            for (int position = cursor; position < group.start(); position += 1) {
                transformed.appendCodePoint(codePointsArray[position]);
            }
            transformed.append(group.winner().marker());
            cursor = group.end();
        }
        for (int position = cursor; position < codePointsArray.length; position += 1) {
            transformed.appendCodePoint(codePointsArray[position]);
        }

        GuardOutcome outcome;
        if (deny) {
            outcome = GuardOutcome.DENY;
        } else if (approval) {
            outcome = GuardOutcome.REQUIRE_APPROVAL;
        } else if (!groups.isEmpty()) {
            outcome = GuardOutcome.REDACT;
        } else {
            outcome = GuardOutcome.ALLOW;
        }

        return new GuardResult.GuardDecision(
                outcome,
                transformed.toString(),
                policyId,
                policyVersion,
                List.copyOf(matched));
    }

    private static GuardResult failure(List<String> reasons) {
        return new GuardResult.GuardFailure(reasons.stream().distinct().sorted().toList());
    }

    private static boolean hasUnpairedSurrogate(String message) {
        for (int index = 0; index < message.length(); index += 1) {
            char value = message.charAt(index);
            if (Character.isHighSurrogate(value)) {
                if (index + 1 >= message.length() || !Character.isLowSurrogate(message.charAt(index + 1))) {
                    return true;
                }
                index += 1;
            } else if (Character.isLowSurrogate(value)) {
                return true;
            }
        }
        return false;
    }

    private record Resolved(int start, int end, GuardRule rule) {}

    private record Group(int start, int end, GuardRule winner) {}
}
