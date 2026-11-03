package local.governance.timeline.api;

import jakarta.servlet.http.HttpServletRequest;
import local.governance.api.EventPageResponse;
import local.governance.api.TraceDetailResponse;
import local.governance.api.TracePageResponse;
import local.governance.timeline.TimelineReadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/traces")
public class TimelineController {

    private final TimelineReadService service;

    public TimelineController(TimelineReadService service) {
        this.service = service;
    }

    @GetMapping
    public TracePageResponse traces(
            HttpServletRequest request,
            @RequestParam(required = false) String cursor) {
        return service.traces(request, cursor);
    }

    @GetMapping("/{traceId}")
    public TraceDetailResponse trace(
            HttpServletRequest request,
            @PathVariable String traceId) {
        return service.trace(request, traceId);
    }

    @GetMapping("/{traceId}/events")
    public EventPageResponse events(
            HttpServletRequest request,
            @PathVariable String traceId,
            @RequestParam(required = false) String cursor) {
        return service.events(request, traceId, cursor);
    }
}
