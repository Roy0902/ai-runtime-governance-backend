package local.governance.api;

import java.util.List;

public record EventPageResponse(List<ActivityEventResponse> items, String nextCursor) {}
