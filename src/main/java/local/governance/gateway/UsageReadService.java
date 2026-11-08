package local.governance.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import local.governance.api.APIException;
import local.governance.authentication.persistence.AuthenticationRepository;
import local.governance.authentication.persistence.AuthenticationRepository.AccountRow;
import local.governance.authentication.session.SessionService;
import local.governance.gateway.api.ModelUsageResponse;
import local.governance.gateway.api.UsageGroupResponse;
import local.governance.gateway.api.UsageSummaryResponse;
import local.governance.timeline.TimelineRepository;
import org.springframework.stereotype.Service;

@Service
public class UsageReadService {

    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
    private static final Duration MAX_RANGE = Duration.ofDays(366);

    private final SessionService sessions;
    private final AuthenticationRepository accounts;
    private final TimelineRepository timeline;
    private final ModelUsageRepository usage;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public UsageReadService(
            SessionService sessions,
            AuthenticationRepository accounts,
            TimelineRepository timeline,
            ModelUsageRepository usage,
            ObjectMapper objectMapper,
            Clock clock) {
        this.sessions = sessions;
        this.accounts = accounts;
        this.timeline = timeline;
        this.usage = usage;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public List<ModelUsageResponse> traceUsage(HttpServletRequest request, String rawTraceId) {
        UUID scope = scope(request);
        UUID traceId = traceId(rawTraceId);
        timeline.trace(scope, traceId).orElseThrow(APIException::notFound);
        return usage.forTrace(scope, traceId).stream().map(this::response).toList();
    }

    public UsageSummaryResponse summary(HttpServletRequest request, String rawFrom, String rawTo) {
        UUID scope = scope(request);
        Instant now = clock.instant();
        Instant to = rawTo == null || rawTo.isBlank() ? now.plusSeconds(1) : instant(rawTo);
        Instant from = rawFrom == null || rawFrom.isBlank()
                ? to.minus(Duration.ofDays(30))
                : instant(rawFrom);
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw APIException.invalid("from", "Choose a range of up to 366 days.");
        }

        ModelUsageRepository.TotalsRow totals = usage.totals(scope, from, to);
        return new UsageSummaryResponse(
                from,
                to,
                totals.calls(),
                totals.inputTokens(),
                totals.outputTokens(),
                totals.cachedInputTokens(),
                totals.reasoningOutputTokens(),
                totals.totalTokens(),
                totals.totalCostUsd(),
                "USD",
                groups(usage.byModel(scope, from, to)),
                groups(usage.byDay(scope, from, to)));
    }

    private List<UsageGroupResponse> groups(List<ModelUsageRepository.SummaryRow> rows) {
        return rows.stream()
                .map(row -> new UsageGroupResponse(
                        row.groupKey(),
                        row.calls(),
                        row.inputTokens(),
                        row.outputTokens(),
                        row.cachedInputTokens(),
                        row.reasoningOutputTokens(),
                        row.totalTokens(),
                        row.totalCostUsd()))
                .toList();
    }

    private ModelUsageResponse response(ModelUsageRow row) {
        return new ModelUsageResponse(
                row.id(),
                row.traceId(),
                row.credentialId(),
                row.clientConversationId(),
                row.clientUserAgent(),
                row.provider(),
                row.requestedModel(),
                row.responseModel(),
                row.responseId(),
                row.systemFingerprint(),
                row.serviceTier(),
                row.stream(),
                row.statusCode(),
                row.outcome(),
                row.finishReasons(),
                row.toolCallNames(),
                row.inputTokens(),
                row.outputTokens(),
                row.cachedInputTokens(),
                row.reasoningOutputTokens(),
                row.totalTokens(),
                row.providerReportedCostUsd(),
                row.inputCostUsd(),
                row.outputCostUsd(),
                row.cachedInputCostUsd(),
                row.totalCostUsd(),
                row.currency(),
                row.requestBytes(),
                row.responseBytes(),
                row.captureTruncated(),
                row.durationMs(),
                row.timeToFirstByteMs(),
                row.errorType(),
                row.errorCode(),
                row.errorMessage(),
                object(row.providerUsageJson()),
                strings(row.responseHeadersJson()),
                row.langfuseObservationId(),
                row.receivedAt());
    }

    private Map<String, Object> object(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, OBJECT_MAP);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private Map<String, String> strings(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, STRING_MAP);
        } catch (Exception exception) {
            return Map.of();
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

    private static Instant instant(String raw) {
        try {
            return Instant.parse(raw);
        } catch (RuntimeException exception) {
            throw APIException.invalid("from", "Use an ISO-8601 instant.");
        }
    }
}
