-- Persist the auditable content of agent activity: prompts, model output,
-- reasoning text and tool-call arguments. Metadata alone is not enough to
-- reconstruct what happened later.
ALTER TABLE activity_event ADD COLUMN content JSONB;
