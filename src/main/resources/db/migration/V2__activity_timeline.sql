CREATE TABLE activity_trace (
    id uuid PRIMARY KEY,
    scope_id uuid NOT NULL,
    received_at timestamptz NOT NULL,
    execution_status text NOT NULL CHECK (execution_status IN ('running', 'completed', 'failed', 'canceled', 'unknown')),
    telemetry_status text NOT NULL CHECK (telemetry_status IN ('complete', 'incomplete', 'unknown', 'unsupported')),
    guard_outcome text CHECK (guard_outcome IN ('allow', 'deny', 'redact', 'require_approval')),
    identity text,
    input_coverage text NOT NULL CHECK (input_coverage IN ('guarded', 'bypassed', 'unknown', 'unsupported', 'degraded')),
    response_coverage text NOT NULL CHECK (response_coverage IN ('observed', 'unknown', 'unsupported', 'degraded')),
    tools_coverage text NOT NULL CHECK (tools_coverage IN ('observed', 'unknown', 'unsupported', 'degraded')),
    loss_cause_code text,
    loss_missing_count integer CHECK (loss_missing_count IS NULL OR loss_missing_count >= 0),
    loss_from timestamptz,
    loss_to timestamptz
);

CREATE INDEX activity_trace_scope_received_idx
    ON activity_trace (scope_id, received_at DESC, id DESC);

CREATE TABLE activity_event (
    id uuid PRIMARY KEY,
    trace_id uuid NOT NULL REFERENCES activity_trace (id) ON DELETE CASCADE,
    scope_id uuid NOT NULL,
    parent_event_id uuid,
    kind text NOT NULL CHECK (kind IN ('prompt', 'guard', 'approval', 'agent', 'model', 'tool')),
    received_at timestamptz NOT NULL,
    source_at timestamptz,
    duration_ms integer CHECK (duration_ms IS NULL OR duration_ms >= 0),
    identity text,
    label text,
    guard_outcome text CHECK (guard_outcome IN ('allow', 'deny', 'redact', 'require_approval')),
    policy_id text,
    policy_version text,
    rule_ids text[] NOT NULL DEFAULT ARRAY[]::text[],
    approval_id uuid,
    approval_state text CHECK (approval_state IN ('requested', 'approved', 'rejected', 'canceled', 'expired')),
    observed_status text CHECK (observed_status IN ('running', 'completed', 'failed', 'canceled', 'unknown')),
    UNIQUE (trace_id, id)
);

CREATE INDEX activity_event_trace_received_idx
    ON activity_event (trace_id, received_at ASC, id ASC);

CREATE INDEX activity_event_scope_idx
    ON activity_event (scope_id);
