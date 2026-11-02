CREATE TABLE account (
    id uuid PRIMARY KEY,
    email text NOT NULL,
    password_hash text NOT NULL,
    email_verified_at timestamptz,
    status text NOT NULL CHECK (status IN ('PENDING', 'VERIFIED', 'DISABLED')),
    mfa_state text NOT NULL CHECK (mfa_state IN ('NONE', 'PENDING', 'ENROLLED', 'RECOVERY_REQUIRED')),
    scope_id uuid,
    gateway_entitlement text NOT NULL DEFAULT 'ABSENT' CHECK (gateway_entitlement IN ('ABSENT', 'GRANTED', 'REVOKED')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    disabled_at timestamptz,
    recovery_required_at timestamptz,
    version bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX account_email_unique ON account (email);
CREATE UNIQUE INDEX account_scope_unique ON account (scope_id) WHERE scope_id IS NOT NULL;

CREATE TABLE web_session (
    id uuid PRIMARY KEY,
    token_hash bytea NOT NULL,
    context text NOT NULL CHECK (context IN ('ANONYMOUS', 'SETUP', 'HUMAN')),
    account_id uuid REFERENCES account (id) ON DELETE CASCADE,
    purpose text,
    csrf_token text NOT NULL,
    created_at timestamptz NOT NULL,
    last_activity_at timestamptz NOT NULL,
    absolute_expires_at timestamptz NOT NULL,
    idle_expires_at timestamptz NOT NULL,
    recent_auth_at timestamptz,
    recent_auth_expires_at timestamptz,
    state text NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE', 'ENDED', 'REVOKED')),
    ended_at timestamptz
);

CREATE UNIQUE INDEX web_session_token_unique ON web_session (token_hash);
CREATE INDEX web_session_account_idx ON web_session (account_id, state);

CREATE TABLE confirmation_link (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    purpose text NOT NULL CHECK (purpose IN ('VERIFY', 'RESET')),
    token_hash bytea NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    superseded_at timestamptz
);

CREATE UNIQUE INDEX confirmation_link_token_unique ON confirmation_link (token_hash);
CREATE UNIQUE INDEX confirmation_link_current_unique
    ON confirmation_link (account_id, purpose)
    WHERE consumed_at IS NULL AND superseded_at IS NULL;

CREATE TABLE mail_request (
    id uuid PRIMARY KEY,
    account_id uuid REFERENCES account (id) ON DELETE SET NULL,
    address text NOT NULL,
    purpose text NOT NULL CHECK (purpose IN ('VERIFY', 'RESET')),
    link_id uuid REFERENCES confirmation_link (id) ON DELETE SET NULL,
    state text NOT NULL CHECK (state IN ('PENDING', 'DELAYED', 'SENDING', 'SENT', 'FAILED_FINAL', 'SUPERSEDED', 'EXPIRED', 'INELIGIBLE_ACCEPTED', 'CANCELLED')),
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    token_nonce bytea,
    token_ciphertext bytea,
    token_key_id text,
    last_error_class text,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE INDEX mail_request_due_idx ON mail_request (state, next_attempt_at);
CREATE INDEX mail_request_address_idx ON mail_request (address, created_at);

CREATE TABLE enrollment_operation (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    purpose text NOT NULL CHECK (purpose IN ('INITIAL_ENROLLMENT', 'RECOVERY_ENROLLMENT', 'AUTHENTICATOR_REPLACEMENT', 'RECOVERY_CODE_GENERATION')),
    state text NOT NULL CHECK (state IN ('PENDING', 'COMPLETED', 'CANCELLED', 'SUPERSEDED', 'EXPIRED')),
    candidate_seed_id uuid,
    recovery_set_id uuid,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    verified_at timestamptz,
    completed_at timestamptz,
    cancelled_at timestamptz,
    superseded_at timestamptz,
    version bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX enrollment_pending_unique
    ON enrollment_operation (account_id, purpose)
    WHERE state = 'PENDING';

CREATE TABLE totp_seed (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    state text NOT NULL CHECK (state IN ('PENDING', 'ACTIVE', 'RETIRED')),
    key_id text NOT NULL,
    nonce bytea NOT NULL,
    ciphertext bytea NOT NULL,
    representation_version integer NOT NULL,
    created_at timestamptz NOT NULL,
    activated_at timestamptz,
    retired_at timestamptz
);

CREATE UNIQUE INDEX totp_seed_active_unique ON totp_seed (account_id) WHERE state = 'ACTIVE';

CREATE TABLE recovery_code_set (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    state text NOT NULL CHECK (state IN ('PENDING', 'ACTIVE', 'REVOKED')),
    created_at timestamptz NOT NULL,
    expires_at timestamptz,
    activated_at timestamptz,
    revoked_at timestamptz
);

CREATE UNIQUE INDEX recovery_set_active_unique ON recovery_code_set (account_id) WHERE state = 'ACTIVE';

CREATE TABLE recovery_code (
    id uuid PRIMARY KEY,
    set_id uuid NOT NULL REFERENCES recovery_code_set (id) ON DELETE CASCADE,
    verifier bytea NOT NULL,
    consumed_at timestamptz
);

CREATE INDEX recovery_code_set_idx ON recovery_code (set_id);

CREATE TABLE totp_used_step (
    account_id uuid NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    time_step bigint NOT NULL,
    accepted_at timestamptz NOT NULL,
    PRIMARY KEY (account_id, time_step)
);

CREATE TABLE rate_event (
    id bigserial PRIMARY KEY,
    bucket_type text NOT NULL,
    bucket_key text NOT NULL,
    occurred_at timestamptz NOT NULL
);

CREATE INDEX rate_event_lookup_idx ON rate_event (bucket_type, bucket_key, occurred_at);

CREATE TABLE rate_bucket_state (
    bucket_type text NOT NULL,
    bucket_key text NOT NULL,
    last_seen_at timestamptz NOT NULL,
    PRIMARY KEY (bucket_type, bucket_key)
);

CREATE TABLE failed_auth_event (
    id bigserial PRIMARY KEY,
    identity text NOT NULL,
    occurred_at timestamptz NOT NULL
);

CREATE INDEX failed_auth_lookup_idx ON failed_auth_event (identity, occurred_at);

CREATE TABLE failed_auth_state (
    identity text PRIMARY KEY,
    cooldown_until timestamptz,
    last_seen_at timestamptz NOT NULL
);

CREATE TABLE host_audit (
    id uuid PRIMARY KEY,
    occurred_at timestamptz NOT NULL,
    operator_id text NOT NULL,
    action text NOT NULL,
    target_account_id uuid,
    reason text NOT NULL,
    outcome text NOT NULL,
    changed_fields jsonb NOT NULL DEFAULT '[]'::jsonb,
    correlation_id uuid NOT NULL
);

CREATE INDEX host_audit_account_idx ON host_audit (target_account_id, occurred_at);

CREATE TABLE seed_key_metadata (
    key_id text PRIMARY KEY,
    state text NOT NULL CHECK (state IN ('INACTIVE', 'ACTIVE', 'RETAINED', 'RETIRED')),
    created_at timestamptz NOT NULL,
    activated_at timestamptz,
    retired_at timestamptz
);
