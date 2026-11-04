package local.governance.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import local.governance.api.APIException;
import local.governance.timeline.TimelineRepository.EventRow;
import local.governance.timeline.TimelineRepository.TraceRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ActivityMetadataServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    private TimelineRepository timeline;
    private ActivityMetadataService service;

    @BeforeEach
    void setUp() {
        timeline = mock(TimelineRepository.class);
        service = new ActivityMetadataService(timeline, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void hookEventCreatesAnObservationOnlyTraceAndStoresNoPayload() {
        UUID scope = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        TraceRow trace = new TraceRow(traceId, scope, NOW, "unknown", "unknown", null,
                "langchain4j", "unknown", "unknown", "unknown", null, null, null, null);
        when(timeline.trace(scope, traceId)).thenReturn(Optional.of(trace));
        when(timeline.contentHash(scope, eventId)).thenReturn(Optional.empty());

        ActivityMetadataService.IngestEvent event = new ActivityMetadataService.IngestEvent();
        event.eventId = eventId;
        event.kind = "model";
        event.label = "chat";
        event.observedStatus = "completed";

        ActivityMetadataService.AcceptResult result =
                service.acceptFromHook(scope, traceId, "langchain4j", event);

        assertTrue(result.accepted());
        assertFalse(result.duplicate());
        verify(timeline).insertTraceIfAbsent(any(TraceRow.class));
        verify(timeline).insertIngestedEvent(any(EventRow.class), any(String.class));
        verify(timeline).observeHookCoverage(scope, traceId, "model");
    }

    @Test
    void rejectsContentFieldsAndGuardAuthorityBeforeCreatingTrace() {
        UUID scope = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        ActivityMetadataService.IngestEvent event = new ActivityMetadataService.IngestEvent();
        event.eventId = UUID.randomUUID();
        event.kind = "prompt";
        event.capture("prompt", "synthetic secret");

        APIException exception = assertThrows(
                APIException.class,
                () -> service.acceptFromHook(scope, traceId, "langfuse", event));

        assertEquals("REQUEST_INVALID", exception.code());
        org.junit.jupiter.api.Assertions.assertFalse(exception.getMessage().contains("synthetic secret"));
        verify(timeline, never()).insertTraceIfAbsent(any(TraceRow.class));

        ActivityMetadataService.IngestEvent forgedGuard = new ActivityMetadataService.IngestEvent();
        forgedGuard.eventId = UUID.randomUUID();
        forgedGuard.kind = "prompt";
        forgedGuard.guardOutcome = "allow";
        APIException authority = assertThrows(
                APIException.class,
                () -> service.acceptFromHook(scope, traceId, "langchain4j", forgedGuard));
        assertEquals("FORBIDDEN", authority.code());
        verify(timeline, never()).insertIngestedEvent(any(EventRow.class), any(String.class));
    }

    @Test
    void rejectsUnboundedAndInvalidMetadata() {
        ActivityMetadataService.IngestEvent event = new ActivityMetadataService.IngestEvent();
        event.eventId = UUID.randomUUID();
        event.kind = "tool";
        event.durationMs = -1;

        APIException exception = assertThrows(
                APIException.class,
                () -> service.acceptFromHook(UUID.randomUUID(), UUID.randomUUID(), "langfuse", event));

        assertEquals("REQUEST_INVALID", exception.code());
        verify(timeline, never()).insertTraceIfAbsent(any(TraceRow.class));
        verify(timeline, never()).observeHookCoverage(any(UUID.class), any(UUID.class), eq("tool"));
    }

    @Test
    void hookRejectsFreeFormLabels() {
        ActivityMetadataService.IngestEvent event = new ActivityMetadataService.IngestEvent();
        event.eventId = UUID.randomUUID();
        event.kind = "agent";
        event.label = "user prompt content";

        APIException exception = assertThrows(
                APIException.class,
                () -> service.acceptFromHook(UUID.randomUUID(), UUID.randomUUID(), "langchain4j", event));

        assertEquals("REQUEST_INVALID", exception.code());
        verify(timeline, never()).insertTraceIfAbsent(any(TraceRow.class));
    }
}
