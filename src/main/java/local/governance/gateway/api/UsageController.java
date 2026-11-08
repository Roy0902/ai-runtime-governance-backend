package local.governance.gateway.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import local.governance.gateway.LangfuseObservationClient;
import local.governance.gateway.UsageReadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/usage")
public class UsageController {

    private final UsageReadService service;

    public UsageController(UsageReadService service) {
        this.service = service;
    }

    @GetMapping("/traces/{traceId}")
    public List<ModelUsageResponse> traceUsage(
            HttpServletRequest request,
            @PathVariable String traceId) {
        return service.traceUsage(request, traceId);
    }

    @GetMapping("/traces/{traceId}/observations/{observationId}")
    public LangfuseObservationClient.ObservationContent observation(
            HttpServletRequest request,
            @PathVariable String traceId,
            @PathVariable String observationId) {
        return service.observation(request, traceId, observationId);
    }

    @GetMapping("/summary")
    public UsageSummaryResponse summary(
            HttpServletRequest request,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return service.summary(request, from, to);
    }
}
