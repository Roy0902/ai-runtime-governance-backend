package local.governance.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GuardDetectorCatalog {

    private static final GuardRule EMAIL = new GuardRule(
            "FX-EMAIL", true, GuardEffect.REDACT, 10, "[REDACTED_EMAIL]");
    private static final GuardRule PHONE = new GuardRule(
            "FX-PHONE", true, GuardEffect.REDACT, 10, "[REDACTED_PHONE]");
    private static final GuardRule CREDENTIAL = new GuardRule(
            "FX-CREDENTIAL", true, GuardEffect.REDACT, 30, "[REDACTED_CREDENTIAL]");
    private static final GuardRule API_TOKEN = new GuardRule(
            "FX-API-TOKEN", true, GuardEffect.REDACT, 30, "[REDACTED_API_TOKEN]");
    private static final GuardRule CARD = new GuardRule(
            "FX-CARD", true, GuardEffect.REDACT, 20, "[REDACTED_PAYMENT_CARD]");
    private static final GuardRule FINANCIAL = new GuardRule(
            "FX-FINANCIAL", true, GuardEffect.REDACT, 20, "[REDACTED_FINANCIAL_ID]");

    private static final List<GuardRule> RULES = List.of(
            EMAIL, PHONE, CREDENTIAL, API_TOKEN, CARD, FINANCIAL);

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,63}"
                    + "(?![A-Za-z0-9._%+-])");
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9])\\+[1-9](?:[0-9][ -]?){6,13}[0-9](?![A-Za-z0-9])");
    private static final Pattern CREDENTIAL_PATTERN = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])(?:password|passwd|secret|client[_-]?secret|private[_-]?key"
                    + "|api[_-]?key|access[_-]?token|refresh[_-]?token)(?![A-Za-z0-9_])\\s*[:=]\\s*"
                    + "(?:\"([^\"\\r\\n]{1,256})\"|'([^'\\r\\n]{1,256})'|([^\\s,;\"']{1,256}))");
    private static final List<Pattern> API_TOKEN_PATTERNS = List.of(
            Pattern.compile("(?<![A-Za-z0-9])AKIA[0-9A-Z]{16}(?![A-Za-z0-9])"),
            Pattern.compile("(?<![A-Za-z0-9])gh[pousr]_[A-Za-z0-9]{20,255}(?![A-Za-z0-9_])"),
            Pattern.compile("(?<![A-Za-z0-9])github_pat_[A-Za-z0-9_]{20,255}(?![A-Za-z0-9_])"),
            Pattern.compile("(?<![A-Za-z0-9])xox[baprs]-[A-Za-z0-9-]{20,255}(?![A-Za-z0-9-])"),
            Pattern.compile("(?<![A-Za-z0-9])sk-(?:(?:ant|proj)-)?[A-Za-z0-9_-]{20,255}(?![A-Za-z0-9_-])"));
    private static final Pattern CARD_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9+])(?:[0-9][ -]?){12,18}[0-9](?![A-Za-z0-9])");
    private static final Pattern IBAN_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9])[A-Z]{2}[0-9]{2}(?: ?[A-Z0-9]){11,30}(?![A-Za-z0-9])",
            Pattern.CASE_INSENSITIVE);

    private final GuardDecisionCore core;

    public GuardDetectorCatalog(GuardDecisionCore core) {
        this.core = Objects.requireNonNull(core);
    }

    public GuardResult evaluate(String policyId, String policyVersion, String message) {
        GuardResult validation = core.evaluate(policyId, policyVersion, message, RULES, List.of());
        if (validation instanceof GuardResult.GuardFailure) {
            return validation;
        }
        return core.evaluate(policyId, policyVersion, message, RULES, detectFindings(message));
    }

    private static List<GuardFinding> detectFindings(String message) {
        int[] codePointOffsets = codePointOffsets(message);
        List<GuardFinding> findings = new ArrayList<>();
        List<Span> credentialValues = addCredentialFindings(message, codePointOffsets, findings);

        for (Pattern pattern : API_TOKEN_PATTERNS) {
            addMatches(message, pattern, API_TOKEN, codePointOffsets, findings,
                    (start, end) -> !containedBy(start, end, credentialValues));
        }
        addMatches(message, EMAIL_PATTERN, EMAIL, codePointOffsets, findings,
                (start, end) -> validEmail(message.substring(start, end)));
        addMatches(message, PHONE_PATTERN, PHONE, codePointOffsets, findings, (start, end) -> true);
        addMatches(message, CARD_PATTERN, CARD, codePointOffsets, findings,
                (start, end) -> validLuhn(message.substring(start, end)));
        addMatches(message, IBAN_PATTERN, FINANCIAL, codePointOffsets, findings,
                (start, end) -> validIban(message.substring(start, end)));
        return List.copyOf(findings);
    }

    private static List<Span> addCredentialFindings(
            String message,
            int[] codePointOffsets,
            List<GuardFinding> findings) {
        Matcher matcher = CREDENTIAL_PATTERN.matcher(message);
        List<Span> values = new ArrayList<>();
        while (findings.size() <= GuardDecisionCore.MAX_FINDINGS && matcher.find()) {
            int group = matchingValueGroup(matcher);
            int start = matcher.start(group);
            int end = matcher.end(group);
            values.add(new Span(start, end));
            addFinding(findings, CREDENTIAL, start, end, codePointOffsets);
        }
        return values;
    }

    private static int matchingValueGroup(Matcher matcher) {
        for (int group = 1; group <= 3; group += 1) {
            if (matcher.start(group) >= 0) {
                return group;
            }
        }
        throw new IllegalStateException("Credential pattern matched without a value.");
    }

    private static void addMatches(
            String message,
            Pattern pattern,
            GuardRule rule,
            int[] codePointOffsets,
            List<GuardFinding> findings,
            MatchFilter filter) {
        Matcher matcher = pattern.matcher(message);
        while (findings.size() <= GuardDecisionCore.MAX_FINDINGS && matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            if (filter.accept(start, end)) {
                addFinding(findings, rule, start, end, codePointOffsets);
            }
        }
    }

    private static void addFinding(
            List<GuardFinding> findings,
            GuardRule rule,
            int start,
            int end,
            int[] codePointOffsets) {
        if (findings.size() <= GuardDecisionCore.MAX_FINDINGS) {
            findings.add(new GuardFinding(rule.id(), codePointOffsets[start], codePointOffsets[end]));
        }
    }

    private static boolean containedBy(int start, int end, List<Span> spans) {
        return spans.stream().anyMatch(span -> start >= span.start() && end <= span.end());
    }

    private static int[] codePointOffsets(String message) {
        int[] offsets = new int[message.length() + 1];
        int utf16 = 0;
        int codePoint = 0;
        while (utf16 < message.length()) {
            int width = Character.charCount(message.codePointAt(utf16));
            for (int index = 0; index < width; index += 1) {
                offsets[utf16 + index] = codePoint;
            }
            utf16 += width;
            codePoint += 1;
            offsets[utf16] = codePoint;
        }
        return offsets;
    }

    private static boolean validEmail(String value) {
        int at = value.indexOf('@');
        if (at <= 0 || at != value.lastIndexOf('@') || at == value.length() - 1
                || value.length() > 254) {
            return false;
        }
        String local = value.substring(0, at);
        if (local.startsWith(".") || local.endsWith(".") || local.contains("..")) {
            return false;
        }
        String[] labels = value.substring(at + 1).split("\\.", -1);
        if (labels.length < 2) {
            return false;
        }
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63
                    || label.startsWith("-") || label.endsWith("-")) {
                return false;
            }
        }
        String topLevel = labels[labels.length - 1];
        return topLevel.length() >= 2 && topLevel.chars().allMatch(Character::isLetter);
    }

    private static boolean validLuhn(String value) {
        int sum = 0;
        boolean doubleDigit = false;
        for (int index = value.length() - 1; index >= 0; index -= 1) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                continue;
            }
            int digit = character - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return sum > 0 && sum % 10 == 0;
    }

    private static boolean validIban(String value) {
        String normalized = value.replace(" ", "").toUpperCase(Locale.ROOT);
        if (normalized.length() < 15 || normalized.length() > 34
                || !normalized.substring(0, 2).chars().allMatch(Character::isLetter)
                || !normalized.substring(2, 4).chars().allMatch(Character::isDigit)) {
            return false;
        }
        String rotated = normalized.substring(4) + normalized.substring(0, 4);
        int remainder = 0;
        for (int index = 0; index < rotated.length(); index += 1) {
            char character = rotated.charAt(index);
            if (character >= '0' && character <= '9') {
                remainder = (remainder * 10 + character - '0') % 97;
            } else if (character >= 'A' && character <= 'Z') {
                remainder = (remainder * 100 + character - 'A' + 10) % 97;
            } else {
                return false;
            }
        }
        return remainder == 1;
    }

    private record Span(int start, int end) {}

    @FunctionalInterface
    private interface MatchFilter {
        boolean accept(int start, int end);
    }
}
