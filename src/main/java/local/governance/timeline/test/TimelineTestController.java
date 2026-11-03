package local.governance.timeline.test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import local.governance.api.APIException;
import local.governance.authentication.persistence.AuthenticationRepository;
import local.governance.authentication.persistence.AuthenticationRepository.AccountRow;
import local.governance.config.GovernanceProperties;
import local.governance.timeline.TimelineRepository;
import local.governance.timeline.TimelineRepository.EventRow;
import local.governance.timeline.TimelineRepository.TraceRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/__test/timeline")
@ConditionalOnProperty(name = "governance.test-control.enabled", havingValue = "true")
public class TimelineTestController {

    private static final Set<String> KINDS =
            Set.of("prompt", "guard", "approval", "agent", "model", "tool");

    private final TimelineRepository timeline;
    private final AuthenticationRepository accounts;
    private final GovernanceProperties properties;
    private final Clock clock;

    public TimelineTestController(
            TimelineRepository timeline,
            AuthenticationRepository accounts,
            GovernanceProperties properties,
            Clock clock) {
        this.timeline = timeline;
        this.accounts = accounts;
        this.properties = properties;
        this.clock = clock;
    }

    @PostMapping("/seed")
    @Transactional
    public Map<String, Object> seed(
            @RequestHeader(value = "X-Test-Token", required = false) String token,
            @RequestBody SeedRequest body) {
        check(token);
        if (body.accountEmail() == null || body.traces() == null) {
            throw APIException.invalid("traces", "Provide an account email and traces.");
        }
        UUID scope = scope(body.accountEmail());

        int traces = 0;
        int events = 0;
        for (SeedTrace trace : body.traces()) {
            if (trace.traceId() == null || trace.receivedAt() == null) {
                throw APIException.invalid("traces", "Each trace needs an id and receipt time.");
            }
            timeline.insertTrace(new TraceRow(
                    trace.traceId(),
                    scope,
                    trace.receivedAt(),
                    orDefault(trace.executionStatus(), "unknown"),
                    orDefault(trace.telemetryStatus(), "unknown"),
                    trace.guardOutcome(),
                    trace.identity(),
                    orDefault(trace.inputCoverage(), "unknown"),
                    orDefault(trace.responseCoverage(), "unknown"),
                    orDefault(trace.toolsCoverage(), "unknown"),
                    trace.lossCauseCode(),
                    trace.lossMissingCount(),
                    trace.lossFrom(),
                    trace.lossTo()));
            traces += 1;
            if (trace.events() == null) {
                continue;
            }
            for (SeedEvent event : trace.events()) {
                insertEvent(scope, trace.traceId(), event);
                events += 1;
            }
        }
        return Map.of("seededTraces", traces, "seededEvents", events);
    }

    private void insertEvent(UUID scope, UUID traceId, SeedEvent event) {
        if (event.eventId() == null || event.receivedAt() == null || !KINDS.contains(event.kind())) {
            throw APIException.invalid("events", "Each event needs a supported kind, id and receipt time.");
        }
        timeline.insertEvent(new EventRow(
                event.eventId(),
                traceId,
                scope,
                event.parentEventId(),
                false,
                event.kind(),
                event.receivedAt(),
                event.sourceAt(),
                event.durationMs(),
                event.identity(),
                event.label(),
                event.guardOutcome(),
                event.policyId(),
                event.policyVersion(),
                event.ruleIds() == null ? List.of() : event.ruleIds(),
                event.approvalId(),
                event.approvalState(),
                event.observedStatus()));
    }

    private UUID scope(String email) {
        AccountRow account = accounts.accountByEmail(email)
                .orElseThrow(() -> APIException.invalid("accountEmail", "Unknown test account."));
        UUID scope = account.scopeId();
        if (scope == null) {
            throw APIException.invalid("accountEmail", "Account has no private scope.");
        }
        return scope;
    }

    @PostMapping("/seed-events")
    @Transactional
    public Map<String, Object> seedEvents(
            @RequestHeader(value = "X-Test-Token", required = false) String token,
            @RequestBody SeedEventsRequest body) {
        check(token);
        UUID scope = scope(body.accountEmail());
        if (body.traceId() == null || timeline.trace(scope, body.traceId()).isEmpty()) {
            throw APIException.invalid("traceId", "Unknown trace.");
        }
        int seeded = 0;
        for (SeedEvent event : body.events() == null ? List.<SeedEvent>of() : body.events()) {
            insertEvent(scope, body.traceId(), event);
            seeded += 1;
        }
        return Map.of("seededEvents", seeded);
    }

    @PostMapping("/bulk-traces")
    @Transactional
    public Map<String, Object> bulkTraces(
            @RequestHeader(value = "X-Test-Token", required = false) String token,
            @RequestBody BulkTracesRequest body) {
        check(token);
        UUID scope = scope(body.accountEmail());
        int count = body.count() == null ? 0 : body.count();
        if (count < 1 || count > 20_000) {
            throw APIException.invalid("count", "Use a count between 1 and 20000.");
        }
        timeline.bulkTraces(scope, count, clock.instant());
        return Map.of("seededTraces", count);
    }

    @PostMapping("/bulk-events")
    @Transactional
    public Map<String, Object> bulkEvents(
            @RequestHeader(value = "X-Test-Token", required = false) String token,
            @RequestBody BulkEventsRequest body) {
        check(token);
        UUID scope = scope(body.accountEmail());
        if (body.traceId() == null || timeline.trace(scope, body.traceId()).isEmpty()) {
            throw APIException.invalid("traceId", "Unknown trace.");
        }
        int count = body.count() == null ? 0 : body.count();
        if (count < 1 || count > 20_000) {
            throw APIException.invalid("count", "Use a count between 1 and 20000.");
        }
        timeline.bulkEvents(scope, body.traceId(), count, clock.instant());
        return Map.of("seededEvents", count);
    }

    @PostMapping("/delete")
    @Transactional
    public Map<String, Object> delete(
            @RequestHeader(value = "X-Test-Token", required = false) String token,
            @RequestBody DeleteRequest body) {
        check(token);
        if (body.traceId() != null) {
            timeline.deleteTrace(body.traceId());
        }
        if (body.eventId() != null) {
            timeline.deleteEvent(body.eventId());
        }
        return Map.of("deleted", true);
    }

    @PostMapping("/reset")
    public Map<String, Object> reset(
            @RequestHeader(value = "X-Test-Token", required = false) String token) {
        check(token);
        timeline.deleteAll();
        return Map.of("reset", true);
    }

    private void check(String token) {
        if (!properties.getTestControl().isEnabled()
                || token == null
                || !token.equals(properties.getTestControl().getToken())) {
            throw new APIException("FORBIDDEN", "Test control is not available.");
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record SeedRequest(String accountEmail, List<SeedTrace> traces) {}

    public record SeedEventsRequest(String accountEmail, UUID traceId, List<SeedEvent> events) {}

    public record BulkTracesRequest(String accountEmail, Integer count) {}

    public record BulkEventsRequest(String accountEmail, UUID traceId, Integer count) {}

    public record DeleteRequest(UUID traceId, UUID eventId) {}

    public record SeedTrace(
            UUID traceId,
            Instant receivedAt,
            String executionStatus,
            String telemetryStatus,
            String guardOutcome,
            String identity,
            String inputCoverage,
            String responseCoverage,
            String toolsCoverage,
            String lossCauseCode,
            Integer lossMissingCount,
            Instant lossFrom,
            Instant lossTo,
            List<SeedEvent> events) {}

    public record SeedEvent(
            UUID eventId,
            UUID parentEventId,
            String kind,
            Instant receivedAt,
            Instant sourceAt,
            Integer durationMs,
            String identity,
            String label,
            String guardOutcome,
            String policyId,
            String policyVersion,
            List<String> ruleIds,
            UUID approvalId,
            String approvalState,
            String observedStatus) {}
}
