package local.governance.timeline;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import local.governance.api.APIException;
import local.governance.api.ActivityEventResponse;
import local.governance.api.CoverageResponse;
import local.governance.api.EventMetadataResponse;
import local.governance.api.EventPageResponse;
import local.governance.api.LossResponse;
import local.governance.api.TraceDetailResponse;
import local.governance.api.TracePageResponse;
import local.governance.api.TraceSummaryResponse;
import local.governance.config.GovernanceProperties;
import local.governance.config.TestFaultRegistry;
import local.governance.security.AppUserService;
import local.governance.timeline.TimelineRepository.EventRow;
import local.governance.timeline.TimelineRepository.TraceRow;
import local.governance.timeline.TimelineRepository.TraversalRow;
import org.springframework.stereotype.Service;

@Service
public class TimelineReadService {

    public static final int TRACE_PAGE_SIZE = 50;
    public static final int EVENT_PAGE_SIZE = 200;

    private final AppUserService users;
    private final TimelineRepository timeline;
    private final TestFaultRegistry faults;
    private final GovernanceProperties properties;
    private final Clock clock;

    public TimelineReadService(
            AppUserService users,
            TimelineRepository timeline,
            TestFaultRegistry faults,
            GovernanceProperties properties,
            Clock clock) {
        this.users = users;
        this.timeline = timeline;
        this.faults = faults;
        this.properties = properties;
        this.clock = clock;
    }

    public TracePageResponse traces(HttpServletRequest request, String cursor) {
        failIfInjected();
        UUID scope = scope(request);
        if (cursor == null || cursor.isBlank()) {
            return startTraces(scope);
        }
        return continueTraces(scope, cursor);
    }

    public TraceDetailResponse trace(HttpServletRequest request, String rawTraceId) {
        failIfInjected();
        UUID scope = scope(request);
        TraceRow row = timeline.trace(scope, traceId(rawTraceId)).orElseThrow(APIException::notFound);
        return detail(row);
    }

    public EventPageResponse events(HttpServletRequest request, String rawTraceId, String cursor) {
        failIfInjected();
        UUID scope = scope(request);
        UUID traceId = traceId(rawTraceId);
        timeline.trace(scope, traceId).orElseThrow(APIException::notFound);
        if (cursor == null || cursor.isBlank()) {
            return startEvents(scope, traceId);
        }
        return continueEvents(scope, traceId, cursor);
    }

    private TracePageResponse startTraces(UUID scope) {
        purgeAndCheck(scope);
        int max = properties.getTimeline().getMaxMembers();
        List<UUID> members = timeline.traceIds(scope, max + 1);
        if (members.size() > max) {
            throw APIException.traversalTooLarge();
        }
        TraversalRow row = create(scope, "TRACE_LIST", null, members);
        return tracePage(scope, row.id(), members, 0);
    }

    private TracePageResponse continueTraces(UUID scope, String cursor) {
        TimelineCursor decoded = TimelineCursor.decode(cursor);
        TraversalRow row = timeline.traversal(decoded.traversalId(), scope)
                .orElseThrow(() -> new APIException("REQUEST_INVALID", "Check the request and try again."));
        if (!"TRACE_LIST".equals(row.kind()) || decoded.position() >= row.memberIds().size()) {
            throw new APIException("REQUEST_INVALID", "Check the request and try again.");
        }
        requireUnexpired(row);
        return tracePage(scope, row.id(), row.memberIds(), decoded.position());
    }

    private EventPageResponse startEvents(UUID scope, UUID traceId) {
        purgeAndCheck(scope);
        int max = properties.getTimeline().getMaxMembers();
        List<UUID> members = timeline.eventIds(scope, traceId, max + 1);
        if (members.size() > max) {
            throw APIException.traversalTooLarge();
        }
        TraversalRow row = create(scope, "EVENT_LIST", traceId, members);
        return eventPage(scope, traceId, row.id(), members, 0);
    }

    private EventPageResponse continueEvents(UUID scope, UUID traceId, String cursor) {
        TimelineCursor decoded = TimelineCursor.decode(cursor);
        TraversalRow row = timeline.traversal(decoded.traversalId(), scope)
                .orElseThrow(() -> new APIException("REQUEST_INVALID", "Check the request and try again."));
        if (!"EVENT_LIST".equals(row.kind())
                || !traceId.equals(row.traceId())
                || decoded.position() >= row.memberIds().size()) {
            throw new APIException("REQUEST_INVALID", "Check the request and try again.");
        }
        requireUnexpired(row);
        return eventPage(scope, traceId, row.id(), row.memberIds(), decoded.position());
    }

    private TraversalRow create(UUID scope, String kind, UUID traceId, List<UUID> members) {
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        Instant expiresAt = now.plus(Duration.ofSeconds(properties.getTimeline().getTtlSeconds()));
        timeline.insertTraversal(id, scope, kind, traceId, members, now, expiresAt);
        return new TraversalRow(id, scope, kind, traceId, members, now, expiresAt);
    }

    private void requireUnexpired(TraversalRow row) {
        if (!clock.instant().isBefore(row.expiresAt())) {
            throw APIException.traversalExpired();
        }
    }

    private void purgeAndCheck(UUID scope) {
        Instant now = clock.instant();
        timeline.deleteExpired(scope, now);
        int active = timeline.activeTraversals(scope, now);
        if (active >= properties.getTimeline().getMaxActive()) {
            int retryAfter = timeline.earliestExpiry(scope, now)
                    .map((expires) -> (int) Math.max(1, Duration.between(now, expires).toSeconds()))
                    .orElse(properties.getTimeline().getTtlSeconds());
            throw APIException.traversalLimit(retryAfter);
        }
    }

    private TracePageResponse tracePage(UUID scope, UUID traversalId, List<UUID> members, int position) {
        int end = Math.min(position + TRACE_PAGE_SIZE, members.size());
        List<UUID> pageIds = members.subList(position, end);
        Map<UUID, TraceRow> rows = timeline.tracesByIds(scope, pageIds).stream()
                .collect(Collectors.toMap(TraceRow::id, Function.identity()));
        List<TraceSummaryResponse> items = pageIds.stream()
                .map((id) -> rows.containsKey(id) ? summary(rows.get(id)) : tombstoneTrace(id))
                .toList();
        String nextCursor = end < members.size() ? TimelineCursor.encode(traversalId, end) : null;
        return new TracePageResponse(items, nextCursor);
    }

    private EventPageResponse eventPage(UUID scope, UUID traceId, UUID traversalId, List<UUID> members, int position) {
        int end = Math.min(position + EVENT_PAGE_SIZE, members.size());
        List<UUID> pageIds = members.subList(position, end);
        Map<UUID, EventRow> rows = timeline.eventsByIds(scope, traceId, pageIds).stream()
                .collect(Collectors.toMap(EventRow::id, Function.identity()));
        List<ActivityEventResponse> items = pageIds.stream()
                .map((id) -> rows.containsKey(id) ? event(rows.get(id)) : tombstoneEvent(traceId, id))
                .toList();
        String nextCursor = end < members.size() ? TimelineCursor.encode(traversalId, end) : null;
        return new EventPageResponse(items, nextCursor);
    }

    private void failIfInjected() {
        if (faults.enabled("timeline-read")) {
            throw APIException.unavailable();
        }
    }

    private UUID scope(HttpServletRequest request) {
        return users.requireScopeId();
    }

    private static UUID traceId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            throw APIException.notFound();
        }
    }

    private static TraceSummaryResponse summary(TraceRow row) {
        return new TraceSummaryResponse(
                row.id(),
                row.receivedAt(),
                row.executionStatus(),
                row.telemetryStatus(),
                row.guardOutcome(),
                false);
    }

    private static TraceSummaryResponse tombstoneTrace(UUID traceId) {
        return new TraceSummaryResponse(traceId, null, null, null, null, true);
    }

    private static TraceDetailResponse detail(TraceRow row) {
        boolean hasLoss = row.lossCauseCode() != null
                || row.lossMissingCount() != null
                || row.lossFrom() != null
                || row.lossTo() != null;
        return new TraceDetailResponse(
                row.id(),
                row.receivedAt(),
                row.executionStatus(),
                row.telemetryStatus(),
                row.guardOutcome(),
                row.identity(),
                new CoverageResponse(row.inputCoverage(), row.responseCoverage(), row.toolsCoverage()),
                hasLoss
                        ? new LossResponse(row.lossCauseCode(), row.lossMissingCount(), row.lossFrom(), row.lossTo())
                        : null);
    }

    private static ActivityEventResponse event(EventRow row) {
        String relationship = row.parentEventId() == null
                ? "root"
                : row.parentExists() ? "linked" : "unresolved";
        EventMetadataResponse metadata = switch (row.kind()) {
            case "prompt" -> new EventMetadataResponse(
                    row.guardOutcome(), null, row.ruleIds(), null, null, null, null, null, null);
            case "guard" -> new EventMetadataResponse(
                    null, row.guardOutcome(), row.ruleIds(), row.policyId(), row.policyVersion(),
                    null, null, null, null);
            case "approval" -> new EventMetadataResponse(
                    null, null, row.ruleIds(), null, null, row.approvalId(), row.approvalState(),
                    null, null);
            default -> new EventMetadataResponse(
                    null, null, row.ruleIds(), null, null, null, null, row.label(), row.observedStatus());
        };
        return new ActivityEventResponse(
                row.id(),
                row.traceId(),
                row.parentEventId(),
                relationship,
                row.kind(),
                row.receivedAt(),
                row.sourceAt(),
                row.durationMs(),
                row.identity(),
                metadata,
                false);
    }

    private static ActivityEventResponse tombstoneEvent(UUID traceId, UUID eventId) {
        return new ActivityEventResponse(
                eventId, traceId, null, null, null, null, null, null, null, null, true);
    }
}
