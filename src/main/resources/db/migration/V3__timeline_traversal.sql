CREATE TABLE timeline_traversal (
    id uuid PRIMARY KEY,
    scope_id uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('TRACE_LIST', 'EVENT_LIST')),
    trace_id uuid,
    member_ids uuid[] NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL
);

CREATE INDEX timeline_traversal_scope_expiry_idx
    ON timeline_traversal (scope_id, expires_at);
