CREATE TABLE model_usage (
    id uuid PRIMARY KEY,
    scope_id uuid NOT NULL,
    trace_id uuid NOT NULL REFERENCES activity_trace (id) ON DELETE CASCADE,
    credential_id uuid REFERENCES gateway_credential (id) ON DELETE SET NULL,
    conversation_key text,
    provider text,
    requested_model text NOT NULL,
    response_model text,
    response_id text,
    system_fingerprint text,
    service_tier text,
    stream boolean NOT NULL,
    status_code integer,
    outcome text NOT NULL CHECK (outcome IN ('completed', 'failed', 'canceled')),
    finish_reasons text[] NOT NULL DEFAULT ARRAY[]::text[],
    tool_call_names text[] NOT NULL DEFAULT ARRAY[]::text[],
    input_tokens bigint,
    output_tokens bigint,
    cached_input_tokens bigint,
    reasoning_output_tokens bigint,
    total_tokens bigint,
    provider_reported_cost_usd numeric(18, 8),
    input_cost_usd numeric(18, 8),
    output_cost_usd numeric(18, 8),
    cached_input_cost_usd numeric(18, 8),
    total_cost_usd numeric(18, 8),
    currency text NOT NULL DEFAULT 'USD',
    request_bytes integer,
    response_bytes bigint,
    capture_truncated boolean NOT NULL DEFAULT false,
    duration_ms integer,
    time_to_first_byte_ms integer,
    error_type text,
    error_code text,
    error_message text,
    provider_usage jsonb,
    response_headers jsonb,
    received_at timestamptz NOT NULL
);

CREATE INDEX model_usage_scope_received_idx
    ON model_usage (scope_id, received_at DESC);

CREATE INDEX model_usage_trace_idx
    ON model_usage (trace_id, received_at ASC);

CREATE INDEX model_usage_model_idx
    ON model_usage (scope_id, requested_model, received_at DESC);
