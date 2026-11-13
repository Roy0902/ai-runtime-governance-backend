package local.governance.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import local.governance.api.ActivityEventResponse;
import local.governance.api.EventPageResponse;
import local.governance.config.GovernanceProperties;
import local.governance.config.TestFaultRegistry;
import local.governance.security.AppUserService;
import local.governance.timeline.TimelineRepository.EventRow;
import local.governance.timeline.TimelineRepository.TraceRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
class TimelineReadServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

    @Mock
    private AppUserService users;
    @Mock
    private TimelineRepository timeline;
    @Mock
    private EventUsageLookup usage;
    @Mock
    private TestFaultRegistry faults;

    private TimelineReadService service;
    private UUID scope;
    private UUID traceId;
    private UUID eventId;

    @BeforeEach
    void setUp() {
        scope = UUID.randomUUID();
        traceId = UUID.randomUUID();
        eventId = UUID.randomUUID();
        service = new TimelineReadService(
                users,
                timeline,
                usage,
                faults,
                new GovernanceProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper());
    }

    @Test
    void listPageReturnsPreviewWithoutContent() {
        when(users.requireScopeId()).thenReturn(scope);
        when(timeline.trace(scope, traceId)).thenReturn(Optional.of(trace()));
        when(timeline.eventIds(scope, traceId, 10_001)).thenReturn(List.of(eventId));
        when(timeline.eventsByIds(scope, traceId, List.of(eventId)))
                .thenReturn(List.of(event("{\"text\":\"Hello   timeline\"}")));
        when(usage.byEvent(scope, traceId)).thenReturn(Map.of());

        EventPageResponse page = service.events(new MockHttpServletRequest(), traceId.toString(), null, false);

        assertThat(page.items()).hasSize(1);
        ActivityEventResponse item = page.items().get(0);
        assertThat(item.content()).isNull();
        assertThat(item.preview()).isEqualTo("Hello timeline");
        assertThat(item.hasContent()).isTrue();
    }

    @Test
    void listPreviewIsCapped() {
        String longText = "x".repeat(400);
        when(users.requireScopeId()).thenReturn(scope);
        when(timeline.trace(scope, traceId)).thenReturn(Optional.of(trace()));
        when(timeline.eventIds(scope, traceId, 10_001)).thenReturn(List.of(eventId));
        when(timeline.eventsByIds(scope, traceId, List.of(eventId)))
                .thenReturn(List.of(event("{\"text\":\"" + longText + "\"}")));
        when(usage.byEvent(scope, traceId)).thenReturn(Map.of());

        EventPageResponse page = service.events(new MockHttpServletRequest(), traceId.toString(), null, false);

        String preview = page.items().get(0).preview();
        assertThat(preview).hasSize(241);
        assertThat(preview).endsWith("…");
    }

    @Test
    void listPreviewRepresentsImageOnlyContent() {
        when(users.requireScopeId()).thenReturn(scope);
        when(timeline.trace(scope, traceId)).thenReturn(Optional.of(trace()));
        when(timeline.eventIds(scope, traceId, 10_001)).thenReturn(List.of(eventId));
        when(timeline.eventsByIds(scope, traceId, List.of(eventId)))
                .thenReturn(List.of(event(
                        "{\"text\":[{\"type\":\"image_url\","
                                + "\"image_url\":{\"url\":\"https://example.test/x.png\"}}]}")));
        when(usage.byEvent(scope, traceId)).thenReturn(Map.of());

        EventPageResponse page = service.events(new MockHttpServletRequest(), traceId.toString(), null, false);

        assertThat(page.items().get(0).preview()).isEqualTo("[image]");
    }

    @Test
    void singleEventReturnsFullContent() {
        when(users.requireScopeId()).thenReturn(scope);
        when(timeline.trace(scope, traceId)).thenReturn(Optional.of(trace()));
        when(timeline.eventsByIds(scope, traceId, List.of(eventId)))
                .thenReturn(List.of(event("{\"text\":\"Full prompt\"}")));
        when(usage.byEvent(scope, traceId)).thenReturn(Map.of());

        ActivityEventResponse item =
                service.event(new MockHttpServletRequest(), traceId.toString(), eventId.toString());

        assertThat(item.content()).isEqualTo(Map.of("text", "Full prompt"));
        assertThat(item.preview()).isNull();
        assertThat(item.hasContent()).isTrue();
    }

    private TraceRow trace() {
        return new TraceRow(
                traceId,
                scope,
                NOW,
                "running",
                "complete",
                null,
                "model",
                null,
                "guarded",
                "unknown",
                "unknown",
                null,
                null,
                null,
                null);
    }

    private EventRow event(String content) {
        return new EventRow(
                eventId,
                traceId,
                scope,
                null,
                false,
                "prompt",
                NOW,
                NOW,
                null,
                "model",
                "chat",
                null,
                null,
                null,
                List.of(),
                null,
                null,
                "completed",
                content);
    }
}
