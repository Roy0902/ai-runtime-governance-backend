package local.governance.gateway;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ModelUsageRepository {

    public record SummaryRow(
            String groupKey,
            long calls,
            long inputTokens,
            long outputTokens,
            long cachedInputTokens,
            long reasoningOutputTokens,
            long totalTokens,
            BigDecimal totalCostUsd) {}

    public record TotalsRow(
            long calls,
            long inputTokens,
            long outputTokens,
            long cachedInputTokens,
            long reasoningOutputTokens,
            long totalTokens,
            BigDecimal totalCostUsd) {}

    private final JdbcClient jdbc;

    public ModelUsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public void insert(ModelUsageRow row) {
        jdbc.sql("""
                INSERT INTO model_usage (
                    id, scope_id, trace_id, credential_id, conversation_key,
                    client_conversation_id, client_user_agent, provider,
                    requested_model, response_model, response_id, system_fingerprint,
                    service_tier, stream, status_code, outcome, finish_reasons,
                    tool_call_names, input_tokens, output_tokens, cached_input_tokens,
                    reasoning_output_tokens, total_tokens, provider_reported_cost_usd,
                    input_cost_usd, output_cost_usd, cached_input_cost_usd,
                    total_cost_usd, currency, request_bytes, response_bytes,
                    capture_truncated, duration_ms, time_to_first_byte_ms, error_type,
                    error_code, error_message, provider_usage, response_headers,
                    langfuse_observation_id, received_at)
                VALUES (
                    :id, :scope, :trace, :credential, :conversation,
                    :clientConversationId, :clientUserAgent, :provider,
                    :requestedModel, :responseModel, :responseId, :fingerprint,
                    :serviceTier, :stream, :statusCode, :outcome,
                    CASE WHEN :reasons = '' THEN ARRAY[]::text[] ELSE string_to_array(:reasons, chr(31)) END,
                    CASE WHEN :toolNames = '' THEN ARRAY[]::text[] ELSE string_to_array(:toolNames, chr(31)) END,
                    :inputTokens, :outputTokens, :cachedTokens, :reasoningTokens,
                    :totalTokens, :providerCost, :inputCost, :outputCost, :cachedCost,
                    :totalCost, :currency, :requestBytes, :responseBytes,
                    :truncated, :durationMs, :ttftMs, :errorType, :errorCode,
                    :errorMessage,
                    CASE WHEN :providerUsage = '' THEN NULL ELSE CAST(:providerUsage AS jsonb) END,
                    CASE WHEN :responseHeaders = '' THEN NULL ELSE CAST(:responseHeaders AS jsonb) END,
                    :langfuseObservationId, :receivedAt)
                """)
                .param("id", row.id())
                .param("scope", row.scopeId())
                .param("trace", row.traceId())
                .param("credential", row.credentialId())
                .param("conversation", row.conversationKey())
                .param("clientConversationId", row.clientConversationId())
                .param("clientUserAgent", row.clientUserAgent())
                .param("provider", row.provider())
                .param("requestedModel", row.requestedModel())
                .param("responseModel", row.responseModel())
                .param("responseId", row.responseId())
                .param("fingerprint", row.systemFingerprint())
                .param("serviceTier", row.serviceTier())
                .param("stream", row.stream())
                .param("statusCode", row.statusCode())
                .param("outcome", row.outcome())
                .param("reasons", join(row.finishReasons()))
                .param("toolNames", join(row.toolCallNames()))
                .param("inputTokens", row.inputTokens())
                .param("outputTokens", row.outputTokens())
                .param("cachedTokens", row.cachedInputTokens())
                .param("reasoningTokens", row.reasoningOutputTokens())
                .param("totalTokens", row.totalTokens())
                .param("providerCost", row.providerReportedCostUsd())
                .param("inputCost", row.inputCostUsd())
                .param("outputCost", row.outputCostUsd())
                .param("cachedCost", row.cachedInputCostUsd())
                .param("totalCost", row.totalCostUsd())
                .param("currency", row.currency() == null ? "USD" : row.currency())
                .param("requestBytes", row.requestBytes())
                .param("responseBytes", row.responseBytes())
                .param("truncated", row.captureTruncated())
                .param("durationMs", row.durationMs())
                .param("ttftMs", row.timeToFirstByteMs())
                .param("errorType", row.errorType())
                .param("errorCode", row.errorCode())
                .param("errorMessage", row.errorMessage())
                .param("providerUsage", row.providerUsageJson() == null ? "" : row.providerUsageJson())
                .param("responseHeaders", row.responseHeadersJson() == null ? "" : row.responseHeadersJson())
                .param("langfuseObservationId", row.langfuseObservationId())
                .param("receivedAt", ts(row.receivedAt()))
                .update();
    }

    public List<ModelUsageRow> forTrace(UUID scopeId, UUID traceId) {
        return jdbc.sql("""
                SELECT * FROM model_usage
                WHERE scope_id = :scope AND trace_id = :trace
                ORDER BY received_at ASC, id ASC
                """)
                .param("scope", scopeId)
                .param("trace", traceId)
                .query(ModelUsageRepository::map)
                .list();
    }

    public Optional<ModelUsageRow> findByObservationId(
            UUID scopeId, UUID traceId, String observationId) {
        return jdbc.sql("""
                SELECT * FROM model_usage
                WHERE scope_id = :scope
                  AND trace_id = :trace
                  AND langfuse_observation_id = :observation
                ORDER BY received_at DESC
                LIMIT 1
                """)
                .param("scope", scopeId)
                .param("trace", traceId)
                .param("observation", observationId)
                .query(ModelUsageRepository::map)
                .optional();
    }

    public List<SummaryRow> byModel(UUID scopeId, Instant from, Instant to) {
        return summary(scopeId, from, to, """
                requested_model AS group_key
                """);
    }

    public List<SummaryRow> byDay(UUID scopeId, Instant from, Instant to) {
        return summary(scopeId, from, to, """
                to_char(date_trunc('day', received_at AT TIME ZONE 'UTC'), 'YYYY-MM-DD') AS group_key
                """);
    }

    public TotalsRow totals(UUID scopeId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT COUNT(*) AS calls,
                       COALESCE(SUM(input_tokens), 0) AS input_tokens,
                       COALESCE(SUM(output_tokens), 0) AS output_tokens,
                       COALESCE(SUM(cached_input_tokens), 0) AS cached_input_tokens,
                       COALESCE(SUM(reasoning_output_tokens), 0) AS reasoning_output_tokens,
                       COALESCE(SUM(total_tokens), 0) AS total_tokens,
                       SUM(total_cost_usd) AS total_cost_usd
                FROM model_usage
                WHERE scope_id = :scope AND received_at >= :from AND received_at < :to
                """)
                .param("scope", scopeId)
                .param("from", ts(from))
                .param("to", ts(to))
                .query((rs, rowNum) -> new TotalsRow(
                        rs.getLong("calls"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"),
                        rs.getLong("cached_input_tokens"),
                        rs.getLong("reasoning_output_tokens"),
                        rs.getLong("total_tokens"),
                        rs.getBigDecimal("total_cost_usd")))
                .single();
    }

    private List<SummaryRow> summary(UUID scopeId, Instant from, Instant to, String groupExpression) {
        return jdbc.sql("""
                SELECT %s,
                       COUNT(*) AS calls,
                       COALESCE(SUM(input_tokens), 0) AS input_tokens,
                       COALESCE(SUM(output_tokens), 0) AS output_tokens,
                       COALESCE(SUM(cached_input_tokens), 0) AS cached_input_tokens,
                       COALESCE(SUM(reasoning_output_tokens), 0) AS reasoning_output_tokens,
                       COALESCE(SUM(total_tokens), 0) AS total_tokens,
                       SUM(total_cost_usd) AS total_cost_usd
                FROM model_usage
                WHERE scope_id = :scope AND received_at >= :from AND received_at < :to
                GROUP BY group_key
                ORDER BY total_tokens DESC, group_key ASC
                """.formatted(groupExpression))
                .param("scope", scopeId)
                .param("from", ts(from))
                .param("to", ts(to))
                .query((rs, rowNum) -> new SummaryRow(
                        rs.getString("group_key"),
                        rs.getLong("calls"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"),
                        rs.getLong("cached_input_tokens"),
                        rs.getLong("reasoning_output_tokens"),
                        rs.getLong("total_tokens"),
                        rs.getBigDecimal("total_cost_usd")))
                .list();
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join(String.valueOf((char) 31), values.stream()
                .filter(value -> value != null && !value.isBlank())
                .toList());
    }

    private static ModelUsageRow map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ModelUsageRow(
                rs.getObject("id", UUID.class),
                rs.getObject("scope_id", UUID.class),
                rs.getObject("trace_id", UUID.class),
                rs.getObject("credential_id", UUID.class),
                rs.getString("conversation_key"),
                rs.getString("client_conversation_id"),
                rs.getString("client_user_agent"),
                rs.getString("provider"),
                rs.getString("requested_model"),
                rs.getString("response_model"),
                rs.getString("response_id"),
                rs.getString("system_fingerprint"),
                rs.getString("service_tier"),
                rs.getBoolean("stream"),
                (Integer) rs.getObject("status_code"),
                rs.getString("outcome"),
                strings(rs, "finish_reasons"),
                strings(rs, "tool_call_names"),
                (Long) rs.getObject("input_tokens"),
                (Long) rs.getObject("output_tokens"),
                (Long) rs.getObject("cached_input_tokens"),
                (Long) rs.getObject("reasoning_output_tokens"),
                (Long) rs.getObject("total_tokens"),
                rs.getBigDecimal("provider_reported_cost_usd"),
                rs.getBigDecimal("input_cost_usd"),
                rs.getBigDecimal("output_cost_usd"),
                rs.getBigDecimal("cached_input_cost_usd"),
                rs.getBigDecimal("total_cost_usd"),
                rs.getString("currency"),
                (Integer) rs.getObject("request_bytes"),
                (Long) rs.getObject("response_bytes"),
                rs.getBoolean("capture_truncated"),
                (Integer) rs.getObject("duration_ms"),
                (Integer) rs.getObject("time_to_first_byte_ms"),
                rs.getString("error_type"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getString("provider_usage"),
                rs.getString("response_headers"),
                rs.getString("langfuse_observation_id"),
                instant(rs.getObject("received_at", OffsetDateTime.class)));
    }

    private static List<String> strings(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Array value = rs.getArray(column);
        if (value == null) {
            return List.of();
        }
        Object array = value.getArray();
        if (array instanceof String[] values) {
            return List.of(values);
        }
        if (array instanceof Object[] values) {
            return java.util.Arrays.stream(values).map(String::valueOf).toList();
        }
        return List.of();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
