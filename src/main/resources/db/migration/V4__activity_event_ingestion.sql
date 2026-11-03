ALTER TABLE activity_event DROP CONSTRAINT activity_event_pkey;
ALTER TABLE activity_event ADD CONSTRAINT activity_event_pkey PRIMARY KEY (scope_id, id);
ALTER TABLE activity_event ADD COLUMN content_hash text;
