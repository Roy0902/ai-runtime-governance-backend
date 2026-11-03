package local.governance.timeline;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import local.governance.api.APIException;
import local.governance.api.ActivityEventResponse;
import local.governance.api.CoverageResponse;
import local.governance.api.EventMetadataResponse;
import local.governance.api.EventPageResponse;
import local.governance.api.LossResponse;
import local.governance.api.TraceDetailResponse;
import local.governance.api.TracePageResponse;
import local.governance.api.TraceSummaryResponse;
import local.governance.authentication.persistence.AuthenticationRepository;
import local.governance.authentication.persistence.AuthenticationRepository.AccountRow;
import local.governance.authentication.session.SessionService;
import local.governance.config.TestFaultRegistry;
import local.governance.timeline.TimelineRepository.EventRow;
import local.governance.timeline.TimelineRepository.TraceRow;
import org.springframework.stereotype.Service;

@Service
public class TimelineReadService {

    public static final int TRACE_PAGE_SIZE = 50;
    public static final int EVENT_PAGE_SIZE = 200;

    private final SessionService sessions;
    private final AuthenticationRepository accounts;
    private final TimelineRepository timeline;
    private final TestFaultRegistry faults;

    public TimelineReadService(
            SessionService sessions,
            AuthenticationRepository accounts,
            TimelineRepository timeline,
            TestFaultRegistry faults) {
        this.sessions = sessions;
        this.accounts = accounts;
        this.timeline = timeline;
        this.faults = faults;
    }

    public TracePageResponse traces(HttpServletRequest request, String cursor) {
        failIfInjected();
        UUID scope = scope(request);
        TimelineCursor after = TimelineCursor.decode(cursor, scope);
        List<TraceRow> rows = after == null
                ? timeline.traces(scope, TRACE_PAGE_SIZE + 1)
                : timeline.tracesAfter(scope, after.receivedAt(), after.id(), TRACE_PAGE_SIZE + 1);
        boolean more = rows.size() > TRACE_PAGE_SIZE;
        List<TraceRow> page = more ? rows.subList(0, TRACE_PAGE_SIZE) : rows;
        String nextCursor = null;
        if (more && !page.isEmpty()) {
            TraceRow last = page.get(page.size() - 1);
            nextCursor = TimelineCursor.encode(scope, last.receivedAt(), last.id());
        }
        return new TracePageResponse(page.stream().map(TimelineReadService::summary).toList(), nextCursor);
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
        TimelineCursor after = TimelineCursor.decode(cursor, scope);
        List<EventRow> rows = after == null
                ? timeline.events(scope, traceId, EVENT_PAGE_SIZE + 1)
                : timeline.eventsAfter(scope, traceId, after.receivedAt(), after.id(), EVENT_PAGE_SIZE + 1);
        boolean more = rows.size() > EVENT_PAGE_SIZE;
        List<EventRow> page = more ? rows.subList(0, EVENT_PAGE_SIZE) : rows;
        String nextCursor = null;
        if (more && !page.isEmpty()) {
            EventRow last = page.get(page.size() - 1);
            nextCursor = TimelineCursor.encode(scope, last.receivedAt(), last.id());
        }
        return new EventPageResponse(page.stream().map(TimelineReadService::event).toList(), nextCursor);
    }

    private void failIfInjected() {
        if (faults.enabled("timeline-read")) {
            throw APIException.unavailable();
        }
    }

    private UUID scope(HttpServletRequest request) {
        SessionService.Current current = sessions.requireHuman(request);
        return accounts.accountById(current.accountId())
                .map(AccountRow::scopeId)
                .orElseThrow(APIException::notFound);
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
                row.guardOutcome());
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
                metadata);
    }
}
