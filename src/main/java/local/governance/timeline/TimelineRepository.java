package local.governance.timeline;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TimelineRepository {

    private final JdbcClient jdbc;

    public TimelineRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record TraceRow(
            UUID id,
            UUID scopeId,
            Instant receivedAt,
            String executionStatus,
            String telemetryStatus,
            String guardOutcome,
            String identity,
            String inputCoverage,
            String responseCoverage,
            String toolsCoverage,
            String lossCauseCode,
            Integer lossMissingCount,
            Instant lossFrom,
            Instant lossTo) {}

    public record EventRow(
            UUID id,
            UUID traceId,
            UUID scopeId,
            UUID parentEventId,
            boolean parentExists,
            String kind,
            Instant receivedAt,
            Instant sourceAt,
            Integer durationMs,
            String identity,
            String label,
            String guardOutcome,
            String policyId,
            String policyVersion,
            List<String> ruleIds,
            UUID approvalId,
            String approvalState,
            String observedStatus) {}

    public List<TraceRow> traces(UUID scopeId, int limit) {
        return jdbc.sql("""
                SELECT * FROM activity_trace
                WHERE scope_id = :scope
                ORDER BY received_at DESC, id DESC
                LIMIT :limit
                """)
                .param("scope", scopeId)
                .param("limit", limit)
                .query(TimelineRepository::mapTrace)
                .list();
    }

    public List<TraceRow> tracesAfter(UUID scopeId, Instant afterReceivedAt, UUID afterId, int limit) {
        return jdbc.sql("""
                SELECT * FROM activity_trace
                WHERE scope_id = :scope
                  AND (received_at, id) < (CAST(:after AS timestamptz), CAST(:afterId AS uuid))
                ORDER BY received_at DESC, id DESC
                LIMIT :limit
                """)
                .param("scope", scopeId)
                .param("after", ts(afterReceivedAt))
                .param("afterId", afterId)
                .param("limit", limit)
                .query(TimelineRepository::mapTrace)
                .list();
    }

    public Optional<TraceRow> trace(UUID scopeId, UUID traceId) {
        return jdbc.sql("""
                SELECT * FROM activity_trace
                WHERE scope_id = :scope AND id = :trace
                """)
                .param("scope", scopeId)
                .param("trace", traceId)
                .query(TimelineRepository::mapTrace)
                .optional();
    }

    public List<EventRow> events(UUID scopeId, UUID traceId, int limit) {
        return jdbc.sql("""
                SELECT e.*, (p.id IS NOT NULL) AS parent_exists
                FROM activity_event e
                LEFT JOIN activity_event p
                  ON p.id = e.parent_event_id AND p.trace_id = e.trace_id
                WHERE e.scope_id = :scope AND e.trace_id = :trace
                ORDER BY e.received_at ASC, e.id ASC
                LIMIT :limit
                """)
                .param("scope", scopeId)
                .param("trace", traceId)
                .param("limit", limit)
                .query(TimelineRepository::mapEvent)
                .list();
    }

    public List<EventRow> eventsAfter(UUID scopeId, UUID traceId, Instant afterReceivedAt, UUID afterId, int limit) {
        return jdbc.sql("""
                SELECT e.*, (p.id IS NOT NULL) AS parent_exists
                FROM activity_event e
                LEFT JOIN activity_event p
                  ON p.id = e.parent_event_id AND p.trace_id = e.trace_id
                WHERE e.scope_id = :scope AND e.trace_id = :trace
                  AND (e.received_at, e.id) > (CAST(:after AS timestamptz), CAST(:afterId AS uuid))
                ORDER BY e.received_at ASC, e.id ASC
                LIMIT :limit
                """)
                .param("scope", scopeId)
                .param("trace", traceId)
                .param("after", ts(afterReceivedAt))
                .param("afterId", afterId)
                .param("limit", limit)
                .query(TimelineRepository::mapEvent)
                .list();
    }

    public void insertTrace(TraceRow row) {
        jdbc.sql("""
                INSERT INTO activity_trace (
                    id, scope_id, received_at, execution_status, telemetry_status,
                    guard_outcome, identity, input_coverage, response_coverage,
                    tools_coverage, loss_cause_code, loss_missing_count, loss_from,
                    loss_to)
                VALUES (
                    :id, :scope, :receivedAt, :executionStatus, :telemetryStatus,
                    :guardOutcome, :identity, :inputCoverage, :responseCoverage,
                    :toolsCoverage, :lossCauseCode, :lossMissingCount, :lossFrom,
                    :lossTo)
                """)
                .param("id", row.id())
                .param("scope", row.scopeId())
                .param("receivedAt", ts(row.receivedAt()))
                .param("executionStatus", row.executionStatus())
                .param("telemetryStatus", row.telemetryStatus())
                .param("guardOutcome", row.guardOutcome())
                .param("identity", row.identity())
                .param("inputCoverage", row.inputCoverage())
                .param("responseCoverage", row.responseCoverage())
                .param("toolsCoverage", row.toolsCoverage())
                .param("lossCauseCode", row.lossCauseCode())
                .param("lossMissingCount", row.lossMissingCount())
                .param("lossFrom", ts(row.lossFrom()))
                .param("lossTo", ts(row.lossTo()))
                .update();
    }

    public void insertEvent(EventRow row) {
        String rules = row.ruleIds() == null ? "" : String.join(",", row.ruleIds());
        jdbc.sql("""
                INSERT INTO activity_event (
                    id, trace_id, scope_id, parent_event_id, kind, received_at,
                    source_at, duration_ms, identity, label, guard_outcome,
                    policy_id, policy_version, rule_ids, approval_id,
                    approval_state, observed_status)
                VALUES (
                    :id, :trace, :scope, :parent, :kind, :receivedAt,
                    :sourceAt, :durationMs, :identity, :label, :guardOutcome,
                    :policyId, :policyVersion,
                    CASE WHEN :rules = '' THEN ARRAY[]::text[] ELSE string_to_array(:rules, ',') END,
                    :approvalId, :approvalState, :observedStatus)
                """)
                .param("id", row.id())
                .param("trace", row.traceId())
                .param("scope", row.scopeId())
                .param("parent", row.parentEventId())
                .param("kind", row.kind())
                .param("receivedAt", ts(row.receivedAt()))
                .param("sourceAt", ts(row.sourceAt()))
                .param("durationMs", row.durationMs())
                .param("identity", row.identity())
                .param("label", row.label())
                .param("guardOutcome", row.guardOutcome())
                .param("policyId", row.policyId())
                .param("policyVersion", row.policyVersion())
                .param("rules", rules)
                .param("approvalId", row.approvalId())
                .param("approvalState", row.approvalState())
                .param("observedStatus", row.observedStatus())
                .update();
    }

    public void deleteAll() {
        jdbc.sql("DELETE FROM activity_event").update();
        jdbc.sql("DELETE FROM activity_trace").update();
    }

    private static TraceRow mapTrace(ResultSet rs, int rowNum) throws SQLException {
        return new TraceRow(
                rs.getObject("id", UUID.class),
                rs.getObject("scope_id", UUID.class),
                instant(rs.getObject("received_at", OffsetDateTime.class)),
                rs.getString("execution_status"),
                rs.getString("telemetry_status"),
                rs.getString("guard_outcome"),
                rs.getString("identity"),
                rs.getString("input_coverage"),
                rs.getString("response_coverage"),
                rs.getString("tools_coverage"),
                rs.getString("loss_cause_code"),
                (Integer) rs.getObject("loss_missing_count"),
                instant(rs.getObject("loss_from", OffsetDateTime.class)),
                instant(rs.getObject("loss_to", OffsetDateTime.class)));
    }

    private static EventRow mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new EventRow(
                rs.getObject("id", UUID.class),
                rs.getObject("trace_id", UUID.class),
                rs.getObject("scope_id", UUID.class),
                rs.getObject("parent_event_id", UUID.class),
                rs.getBoolean("parent_exists"),
                rs.getString("kind"),
                instant(rs.getObject("received_at", OffsetDateTime.class)),
                instant(rs.getObject("source_at", OffsetDateTime.class)),
                (Integer) rs.getObject("duration_ms"),
                rs.getString("identity"),
                rs.getString("label"),
                rs.getString("guard_outcome"),
                rs.getString("policy_id"),
                rs.getString("policy_version"),
                rules(rs, "rule_ids"),
                rs.getObject("approval_id", UUID.class),
                rs.getString("approval_state"),
                rs.getString("observed_status"));
    }

    private static List<String> rules(ResultSet rs, String column) throws SQLException {
        java.sql.Array value = rs.getArray(column);
        if (value == null) {
            return List.of();
        }
        Object array = value.getArray();
        if (array instanceof String[] strings) {
            return Arrays.asList(strings);
        }
        return List.of();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
