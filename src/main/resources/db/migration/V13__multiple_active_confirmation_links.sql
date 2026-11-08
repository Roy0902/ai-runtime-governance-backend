-- Allow more than one unexpired confirmation link to exist for the same
-- account/purpose. A user may use any valid link; successful verification or
-- password reset invalidates the remaining links in the same purpose.
DROP INDEX IF EXISTS confirmation_link_current_unique;

CREATE INDEX IF NOT EXISTS confirmation_link_active_idx
    ON confirmation_link (account_id, purpose, expires_at)
    WHERE consumed_at IS NULL AND superseded_at IS NULL;
