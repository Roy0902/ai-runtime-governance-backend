-- Groups own shared LangSmith/Langfuse credentials. Members join through a
-- one-time invite token and then issue their own gateway key in group context.

CREATE TABLE app_group (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    owner_user_id UUID NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE group_member (
    group_id UUID NOT NULL REFERENCES app_group(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    role TEXT NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (group_id, user_id)
);

CREATE TABLE group_invite (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES app_group(id) ON DELETE CASCADE,
    token_hash BYTEA NOT NULL,
    token_prefix TEXT NOT NULL,
    created_by UUID NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    max_uses INT NOT NULL DEFAULT 0,
    uses INT NOT NULL DEFAULT 0,
    revoked_at TIMESTAMPTZ
);

CREATE INDEX group_invite_hash_idx ON group_invite (token_hash);
CREATE INDEX group_member_user_idx ON group_member (user_id);

ALTER TABLE provider_credential ADD COLUMN group_id UUID REFERENCES app_group(id) ON DELETE CASCADE;
ALTER TABLE provider_credential ALTER COLUMN account_id DROP NOT NULL;
CREATE UNIQUE INDEX provider_credential_group_provider_idx
    ON provider_credential (group_id, provider) WHERE group_id IS NOT NULL;
ALTER TABLE provider_credential ADD CONSTRAINT provider_credential_owner_check
    CHECK ((account_id IS NULL) <> (group_id IS NULL));

ALTER TABLE gateway_credential ADD COLUMN group_id UUID REFERENCES app_group(id) ON DELETE CASCADE;
CREATE INDEX gateway_credential_group_idx ON gateway_credential (group_id);
