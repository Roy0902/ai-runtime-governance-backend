package local.governance.timeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
import local.governance.api.ModelUsageSummaryResponse;
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
    private final EventUsageLookup usage;
    private final TestFaultRegistry faults;
    private final GovernanceProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public TimelineReadService(
            AppUserService users,
            TimelineRepository timeline,
            EventUsageLookup usage,
            TestFaultRegistry faults,
            GovernanceProperties properties,
            Clock clock,
            ObjectMapper objectMapper) {
        this.users = users;
        this.timeline = timeline;
        this.usage = usage;
        this.faults = faults;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
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

    /**
     * Live trace polling. Returns only traces received after the supplied
     * cursor, newest first, without creating a traversal row.
     */
    public TracePageResponse liveTraces(HttpServletRequest request, String since) {
        failIfInjected();
        UUID scope = scope(request);
        LiveCursor cursor = LiveCursor.decode(since);
        List<UUID> members;
        if (cursor == null) {
            members = timeline.traceIds(scope, TRACE_PAGE_SIZE);
        } else {
            members = new ArrayList<>(
                    timeline.traceIdsAfter(scope, cursor.receivedAt(), cursor.id(), TRACE_PAGE_SIZE));
            Collections.reverse(members);
        }
        Map<UUID, TraceRow> rows = timeline.tracesByIds(scope, members).stream()
                .collect(Collectors.toMap(TraceRow::id, Function.identity()));
        List<TraceSummaryResponse> items = members.stream()
                .map((id) -> rows.containsKey(id) ? summary(rows.get(id)) : tombstoneTrace(id))
                .toList();
        return new TracePageResponse(items, null);
    }

    /**
     * Live event polling. Returns only events received after the supplied
     * cursor, oldest first, without creating a traversal row.
     */
    public EventPageResponse liveEvents(HttpServletRequest request, String rawTraceId, String since) {
        failIfInjected();
        UUID scope = scope(request);
        UUID traceId = traceId(rawTraceId);
        timeline.trace(scope, traceId).orElseThrow(APIException::notFound);
        LiveCursor cursor = LiveCursor.decode(since);
        List<EventRow> rows = cursor == null
                ? timeline.events(scope, traceId, EVENT_PAGE_SIZE)
                : timeline.eventsAfter(scope, traceId, cursor.receivedAt(), cursor.id(), EVENT_PAGE_SIZE);
        Map<UUID, ModelUsageSummaryResponse> usageByEvent = usage.byEvent(scope, traceId);
        List<ActivityEventResponse> items = rows.stream()
                .map((row) -> event(row, usageByEvent.get(row.id())))
                .toList();
        return new EventPageResponse(items, null);
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
        // Reads must never be throttled: the timeline is expected to refresh in
        // real time. Expired traversals are purged here, and the TTL bounds the
        // number of rows that can remain between reads.
        timeline.deleteExpired(scope, clock.instant());
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
        Map<UUID, ModelUsageSummaryResponse> usageByEvent = usage.byEvent(scope, traceId);
        List<ActivityEventResponse> items = pageIds.stream()
                .map((id) -> rows.containsKey(id)
                        ? event(rows.get(id), usageByEvent.get(id))
                        : tombstoneEvent(traceId, id))
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
                row.sessionId(),
                false);
    }

    private static TraceSummaryResponse tombstoneTrace(UUID traceId) {
        return new TraceSummaryResponse(traceId, null, null, null, null, null, true);
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
                row.sessionId(),
                new CoverageResponse(row.inputCoverage(), row.responseCoverage(), row.toolsCoverage()),
                hasLoss
                        ? new LossResponse(row.lossCauseCode(), row.lossMissingCount(), row.lossFrom(), row.lossTo())
                        : null);
    }

    private ActivityEventResponse event(EventRow row, ModelUsageSummaryResponse usage) {
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
                usage,
                content(row.content()),
                false);
    }

    private Object content(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (Exception exception) {
            return null;
        }
    }

    private static ActivityEventResponse tombstoneEvent(UUID traceId, UUID eventId) {
        return new ActivityEventResponse(
                eventId, traceId, null, null, null, null, null, null, null, null, null, null, true);
    }
}
