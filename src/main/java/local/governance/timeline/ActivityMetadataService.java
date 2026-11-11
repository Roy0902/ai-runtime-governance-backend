package local.governance.timeline;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import local.governance.api.APIException;
import local.governance.timeline.TimelineRepository.EventRow;
import local.governance.timeline.TimelineRepository.TraceRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Trusted metadata ingestion applies the field allowlist, producer authority,
 * duplicate idempotency and conflict rejection before anything is persisted.
 */
@Service
public class ActivityMetadataService {

    private static final Set<String> KINDS =
            Set.of("prompt", "guard", "approval", "agent", "model", "tool");
    private static final Set<String> OBSERVED =
            Set.of("prompt", "agent", "model", "tool");

    private final TimelineRepository timeline;
    private final Clock clock;

    public ActivityMetadataService(TimelineRepository timeline, Clock clock) {
        this.timeline = timeline;
        this.clock = clock;
    }

    public record AcceptResult(boolean accepted, boolean duplicate) {}

    /** Mutable payload so unknown/content-bearing fields can be captured and rejected. */
    public static final class IngestEvent {
        public UUID eventId;
        public String kind;
        public UUID parentEventId;
        public Instant sourceAt;
        public Integer durationMs;
        public String identity;
        public String label;
        public String guardOutcome;
        public String policyId;
        public String policyVersion;
        public List<String> ruleIds;
        public UUID approvalId;
        public String approvalState;
        public String observedStatus;

        private final Map<String, Object> unknown = new LinkedHashMap<>();

        @JsonAnySetter
        public void capture(String name, Object value) {
            unknown.put(name, value);
        }

        public boolean hasUnknown() {
            return !unknown.isEmpty();
        }
    }

    @Transactional
    public AcceptResult accept(UUID scope, UUID traceId, String producer, IngestEvent event) {
        validate(producer, event);
        timeline.trace(scope, traceId).orElseThrow(APIException::notFound);
        return persist(scope, traceId, event);
    }

    @Transactional
    public AcceptResult acceptFromHook(UUID scope, UUID traceId, String integration, IngestEvent event) {
        validate("agent_reporter", event);
        validateHookMetadata(event);
        Instant receivedAt = clock.instant();
        timeline.insertTraceIfAbsent(new TraceRow(
                traceId,
                scope,
                receivedAt,
                "unknown",
                "unknown",
                null,
                integration,
                "unknown",
                "unknown",
                "unknown",
                null,
                null,
                null,
                null));
        timeline.trace(scope, traceId).orElseThrow(APIException::notFound);
        return persist(scope, traceId, event);
    }

    private AcceptResult persist(UUID scope, UUID traceId, IngestEvent event) {
        String hash = hash(event);
        Optional<String> existing = timeline.contentHash(scope, event.eventId);
        if (existing.isPresent()) {
            if (existing.get().equals(hash)) {
                return new AcceptResult(true, true);
            }
            throw new APIException("EVENT_CONFLICT", "This event conflicts with retained history.");
        }

        List<String> rules = event.ruleIds == null ? List.of() : event.ruleIds;
        timeline.insertIngestedEvent(new EventRow(
                event.eventId,
                traceId,
                scope,
                event.parentEventId,
                false,
                event.kind,
                clock.instant(),
                event.sourceAt,
                event.durationMs,
                event.identity,
                event.label,
                event.guardOutcome,
                event.policyId,
                event.policyVersion,
                rules,
                event.approvalId,
                event.approvalState,
                event.observedStatus,
                null), hash);
        timeline.observeHookCoverage(scope, traceId, event.kind);
        return new AcceptResult(true, false);
    }

    private static void validate(String producer, IngestEvent event) {
        if (event == null || event.hasUnknown() || event.eventId == null
                || event.kind == null || !KINDS.contains(event.kind)) {
            throw APIException.invalid("event", "Provide an event id, supported kind and supported fields.");
        }
        if (event.durationMs != null && event.durationMs < 0
                || tooLong(event.identity, 128)
                || tooLong(event.label, 256)
                || tooLong(event.policyId, 128)
                || tooLong(event.policyVersion, 128)
                || event.ruleIds != null && (event.ruleIds.size() > 32
                        || event.ruleIds.stream().anyMatch(rule -> rule == null || rule.isBlank() || rule.length() > 128))
                || !oneOf(event.guardOutcome, "allow", "deny", "redact", "require_approval")
                || !oneOf(event.approvalState, "requested", "approved", "rejected", "canceled", "expired")
                || !oneOf(event.observedStatus, "running", "completed", "failed", "canceled", "unknown")) {
            throw APIException.invalid("event", "Provide valid, bounded event metadata.");
        }
        boolean guardFacts = event.guardOutcome != null
                || event.policyId != null
                || event.policyVersion != null
                || (event.ruleIds != null && !event.ruleIds.isEmpty());
        boolean approvalFacts = event.approvalId != null || event.approvalState != null;

        switch (producer == null ? "" : producer) {
            case "agent_reporter" -> {
                if (!OBSERVED.contains(event.kind) || guardFacts || approvalFacts) {
                    throw forbidden();
                }
            }
            case "guard_service" -> {
                if (!"guard".equals(event.kind) || approvalFacts || event.guardOutcome == null) {
                    throw forbidden();
                }
            }
            case "approval_service" -> {
                if (!"approval".equals(event.kind)
                        || guardFacts
                        || event.approvalId == null
                        || event.approvalState == null) {
                    throw forbidden();
                }
            }
            case "test_fixture" -> {
                // Isolated fixtures may report any allowed kind.
            }
            default -> throw forbidden();
        }
    }

    private static APIException forbidden() {
        return new APIException("FORBIDDEN", "This producer cannot report that event.");
    }

    private static boolean tooLong(String value, int maxLength) {
        return value != null && value.length() > maxLength;
    }

    private static void validateHookMetadata(IngestEvent event) {
        if (!identifier(event.identity, 128) || !identifier(event.label, 128)) {
            throw APIException.invalid("event", "Use short metadata identifiers without free-form text.");
        }
    }

    private static boolean identifier(String value, int maxLength) {
        return value == null
                || value.length() <= maxLength
                        && value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*");
    }

    private static boolean oneOf(String value, String... allowed) {
        if (value == null) {
            return true;
        }
        return java.util.Arrays.asList(allowed).contains(value);
    }

    private static String hash(IngestEvent event) {
        List<String> rules = event.ruleIds == null
                ? List.of()
                : event.ruleIds.stream().sorted().toList();
        String canonical = String.join("|",
                text(event.kind),
                text(event.parentEventId),
                text(event.sourceAt),
                text(event.durationMs),
                text(event.identity),
                text(event.label),
                text(event.guardOutcome),
                text(event.policyId),
                text(event.policyVersion),
                String.join(",", rules),
                text(event.approvalId),
                text(event.approvalState),
                text(event.observedStatus));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
