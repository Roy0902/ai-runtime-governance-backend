CREATE TABLE gateway_credential (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    scope_id uuid NOT NULL,
    name text NOT NULL,
    token_hash bytea NOT NULL UNIQUE,
    token_prefix text NOT NULL,
    created_at timestamptz NOT NULL,
    last_used_at timestamptz,
    revoked_at timestamptz
);

CREATE UNIQUE INDEX gateway_credential_active_name_unique
    ON gateway_credential (account_id, lower(name))
    WHERE revoked_at IS NULL;

CREATE INDEX gateway_credential_account_idx
    ON gateway_credential (account_id, created_at DESC);

CREATE TABLE gateway_conversation (
    conversation_key text PRIMARY KEY,
    scope_id uuid NOT NULL,
    trace_id uuid NOT NULL REFERENCES activity_trace (id) ON DELETE CASCADE,
    guarded_message_hash text NOT NULL,
    guard_outcome text NOT NULL CHECK (guard_outcome IN ('allow', 'redact', 'require_approval')),
    redacted boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL
);

CREATE INDEX gateway_conversation_scope_idx
    ON gateway_conversation (scope_id, expires_at);

CREATE INDEX gateway_conversation_expiry_idx
    ON gateway_conversation (expires_at);
