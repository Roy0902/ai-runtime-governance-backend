CREATE TABLE hook_credential (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    scope_id uuid NOT NULL,
    integration text NOT NULL CHECK (integration IN ('langchain4j', 'langfuse')),
    token_hash bytea NOT NULL UNIQUE,
    token_prefix text NOT NULL,
    created_at timestamptz NOT NULL,
    revoked_at timestamptz
);

CREATE UNIQUE INDEX hook_credential_active_unique
    ON hook_credential (account_id, integration)
    WHERE revoked_at IS NULL;

CREATE INDEX hook_credential_scope_idx
    ON hook_credential (scope_id, integration)
    WHERE revoked_at IS NULL;
