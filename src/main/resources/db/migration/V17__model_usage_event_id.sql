ALTER TABLE model_usage ADD COLUMN event_id uuid;

CREATE INDEX model_usage_event_idx
    ON model_usage (event_id)
    WHERE event_id IS NOT NULL;
