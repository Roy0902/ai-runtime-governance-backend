-- Provider credentials are per account. The secret is sealed with an
-- envelope: a random AES-256-GCM data key encrypts the secret, and that data
-- key is wrapped with the deployment's RSA public key. The private key never
-- enters the database.

CREATE TABLE provider_credential (
    id UUID PRIMARY KEY,
    app_user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    provider TEXT NOT NULL,
    name TEXT,
    access_key TEXT,
    secret_hint TEXT,
    secret_nonce BYTEA NOT NULL,
    secret_ciphertext BYTEA NOT NULL,
    wrapped_key BYTEA NOT NULL,
    key_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (app_user_id, provider)
);

CREATE INDEX provider_credential_account_idx ON provider_credential (app_user_id);
