-- Keycloak cutover: re-key application-owned credentials and audit rows from
-- the legacy account table to app_user. Existing IDs are preserved because the
-- app_user rows were copied from account in V9.
ALTER TABLE gateway_credential
    DROP CONSTRAINT IF EXISTS gateway_credential_account_id_fkey;

ALTER TABLE gateway_credential
    RENAME COLUMN account_id TO app_user_id;

ALTER TABLE gateway_credential
    ADD CONSTRAINT gateway_credential_app_user_id_fkey
    FOREIGN KEY (app_user_id) REFERENCES app_user (id) ON DELETE RESTRICT;

ALTER INDEX IF EXISTS gateway_credential_account_idx
    RENAME TO gateway_credential_app_user_idx;

ALTER TABLE hook_credential
    DROP CONSTRAINT IF EXISTS hook_credential_account_id_fkey;

ALTER TABLE hook_credential
    RENAME COLUMN account_id TO app_user_id;

ALTER TABLE hook_credential
    ADD CONSTRAINT hook_credential_app_user_id_fkey
    FOREIGN KEY (app_user_id) REFERENCES app_user (id) ON DELETE RESTRICT;

ALTER TABLE host_audit
    RENAME COLUMN target_account_id TO target_user_id;

ALTER TABLE host_audit
    ADD CONSTRAINT host_audit_target_user_id_fkey
    FOREIGN KEY (target_user_id) REFERENCES app_user (id) ON DELETE SET NULL;

ALTER TABLE gateway_credential
    ADD CONSTRAINT gateway_credential_scope_id_fkey
    FOREIGN KEY (scope_id) REFERENCES app_scope (id) ON DELETE RESTRICT;

ALTER TABLE hook_credential
    ADD CONSTRAINT hook_credential_scope_id_fkey
    FOREIGN KEY (scope_id) REFERENCES app_scope (id) ON DELETE RESTRICT;

ALTER TABLE activity_trace
    ADD CONSTRAINT activity_trace_scope_id_fkey
    FOREIGN KEY (scope_id) REFERENCES app_scope (id) ON DELETE RESTRICT;

ALTER TABLE activity_event
    ADD CONSTRAINT activity_event_scope_id_fkey
    FOREIGN KEY (scope_id) REFERENCES app_scope (id) ON DELETE RESTRICT;

ALTER TABLE timeline_traversal
    ADD CONSTRAINT timeline_traversal_scope_id_fkey
    FOREIGN KEY (scope_id) REFERENCES app_scope (id) ON DELETE CASCADE;

ALTER TABLE gateway_conversation
    ADD CONSTRAINT gateway_conversation_scope_id_fkey
    FOREIGN KEY (scope_id) REFERENCES app_scope (id) ON DELETE CASCADE;

ALTER TABLE model_usage
    ADD CONSTRAINT model_usage_scope_id_fkey
    FOREIGN KEY (scope_id) REFERENCES app_scope (id) ON DELETE RESTRICT;
