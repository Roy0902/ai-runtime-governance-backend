-- Keep the legacy native account projection in sync with the application
-- identity tables while both native and Keycloak login remain available.
CREATE OR REPLACE FUNCTION sync_native_account_to_app_user()
RETURNS trigger AS $$
BEGIN
    INSERT INTO app_user (
        id, keycloak_sub, email, display_name, status,
        gateway_entitlement, created_at, updated_at, disabled_at, version)
    VALUES (
        NEW.id,
        NULL,
        NEW.email,
        NULL,
        CASE WHEN NEW.status = 'DISABLED' THEN 'DISABLED' ELSE 'ACTIVE' END,
        NEW.gateway_entitlement,
        NEW.created_at,
        NEW.updated_at,
        NEW.disabled_at,
        NEW.version)
    ON CONFLICT (id) DO UPDATE SET
        email = EXCLUDED.email,
        status = EXCLUDED.status,
        gateway_entitlement = EXCLUDED.gateway_entitlement,
        updated_at = EXCLUDED.updated_at,
        disabled_at = EXCLUDED.disabled_at,
        version = EXCLUDED.version;

    IF NEW.scope_id IS NOT NULL THEN
        INSERT INTO app_scope (id, owner_user_id, created_at)
        VALUES (NEW.scope_id, NEW.id, NEW.created_at)
        ON CONFLICT (id) DO NOTHING;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_sync_app_user
AFTER INSERT OR UPDATE ON account
FOR EACH ROW EXECUTE FUNCTION sync_native_account_to_app_user();
