package local.governance.api;

import java.util.List;

public record TracePageResponse(List<TraceSummaryResponse> items, String nextCursor) {}
