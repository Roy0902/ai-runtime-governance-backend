-- Application-owned identity projection.
--
-- Keycloak owns authentication, passwords, OTP credentials, recovery, email
-- verification and SSO sessions. This table owns only application profile,
-- private scope and gateway entitlement.
CREATE TABLE app_user (
    id uuid PRIMARY KEY,
    keycloak_sub text,
    email text NOT NULL,
    display_name text,
    status text NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    gateway_entitlement text NOT NULL DEFAULT 'ABSENT'
        CHECK (gateway_entitlement IN ('ABSENT', 'GRANTED', 'REVOKED')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    disabled_at timestamptz,
    version bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX app_user_keycloak_sub_unique
    ON app_user (keycloak_sub)
    WHERE keycloak_sub IS NOT NULL;

CREATE UNIQUE INDEX app_user_email_unique
    ON app_user (lower(email));

CREATE INDEX app_user_status_idx
    ON app_user (status);

CREATE TABLE app_scope (
    id uuid PRIMARY KEY,
    owner_user_id uuid NOT NULL REFERENCES app_user (id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL
);

CREATE UNIQUE INDEX app_scope_owner_unique
    ON app_scope (owner_user_id);

-- Preserve existing local accounts while the native-auth path is still active.
-- The Keycloak cutover fills keycloak_sub and then drops the old auth tables.
INSERT INTO app_user (
    id, keycloak_sub, email, display_name, status, gateway_entitlement,
    created_at, updated_at, disabled_at, version
)
SELECT
    id,
    NULL,
    email,
    NULL,
    CASE WHEN status = 'DISABLED' THEN 'DISABLED' ELSE 'ACTIVE' END,
    gateway_entitlement,
    created_at,
    updated_at,
    disabled_at,
    version
FROM account
ON CONFLICT (id) DO NOTHING;

INSERT INTO app_scope (id, owner_user_id, created_at)
SELECT scope_id, id, created_at
FROM account
WHERE scope_id IS NOT NULL
ON CONFLICT (id) DO NOTHING;
