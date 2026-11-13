package local.governance.timeline.api;

import jakarta.servlet.http.HttpServletRequest;
import local.governance.api.EventPageResponse;
import local.governance.api.TracePageResponse;
import local.governance.timeline.TimelineReadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Polling endpoints for the live timeline.
 *
 * <p>They deliberately live on a separate path from the paginated read API so
 * an older backend returns 404 instead of silently creating traversal rows for
 * every poll.
 */
@RestController
@RequestMapping("/api/v1/timeline")
public class TimelineLiveController {

    private final TimelineReadService service;

    public TimelineLiveController(TimelineReadService service) {
        this.service = service;
    }

    @GetMapping("/traces")
    public TracePageResponse traces(
            HttpServletRequest request,
            @RequestParam(required = false) String since) {
        return service.liveTraces(request, since);
    }

    @GetMapping("/traces/{traceId}/events")
    public EventPageResponse events(
            HttpServletRequest request,
            @PathVariable String traceId,
            @RequestParam(required = false) String since,
            @RequestParam(defaultValue = "false") boolean includeContent) {
        return service.liveEvents(request, traceId, since, includeContent);
    }
}
