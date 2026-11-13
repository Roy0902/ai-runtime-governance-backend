ALTER TABLE activity_trace ADD COLUMN session_id text;

CREATE INDEX activity_trace_scope_session_received_idx
    ON activity_trace (scope_id, session_id, received_at DESC, id DESC);
