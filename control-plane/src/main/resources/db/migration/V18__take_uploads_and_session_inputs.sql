-- Uploads and what a session takes (ADR 0019).

-- One file a renter uploaded, straight to storage through pre-signed multipart
-- links: the control plane never carries the bytes. filename is the original,
-- for display; stored_name keeps only [A-Za-z0-9._-] and is the object's name,
-- uploads/{id}/{stored_name}, and the file's name in the job's input folder.
-- A pending upload has a multipart upload open in storage; it becomes ready
-- once completed at the declared size, or failed when it isn't. Pending ones
-- expire after a day, ready ones 30 days after completion, never while a
-- session that hasn't ended uses them; deleted ones keep their row, without
-- their object. A repeat of a create request (the same idempotency_key and
-- request_hash) returns the same upload with fresh links, so signed links are
-- never stored.
CREATE TABLE sessions.uploads (
    id              uuid        PRIMARY KEY,
    user_id         uuid        NOT NULL,
    filename        text        NOT NULL CHECK (octet_length(filename) BETWEEN 1 AND 255),
    stored_name     text        NOT NULL CHECK (stored_name ~ '^[A-Za-z0-9._-]{1,255}$'
                                                AND stored_name NOT IN ('.', '..')),
    size_bytes      bigint      NOT NULL CHECK (size_bytes > 0),
    object_key      text        NOT NULL UNIQUE,
    multipart_id    text,
    part_size_bytes bigint      NOT NULL CHECK (part_size_bytes > 0),
    status          text        NOT NULL CHECK (status IN ('pending', 'ready', 'failed', 'expired', 'deleted')),
    idempotency_key text        NOT NULL,
    request_hash    text        NOT NULL,
    created_at      timestamptz NOT NULL,
    completed_at    timestamptz,
    expires_at      timestamptz NOT NULL,
    version         integer     NOT NULL,
    UNIQUE (user_id, idempotency_key),
    CHECK (status <> 'pending' OR multipart_id IS NOT NULL),
    CHECK (status <> 'ready' OR completed_at IS NOT NULL)
);

CREATE INDEX uploads_by_user ON sessions.uploads (user_id, id DESC);
CREATE INDEX uploads_to_expire ON sessions.uploads (expires_at) WHERE status IN ('pending', 'ready');

-- A session's parameters, resolved against its template with defaults filled
-- in, and its input: exactly one of an upload or an https URL, or neither.
ALTER TABLE sessions.sessions ADD COLUMN params jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE sessions.sessions ADD COLUMN input_upload_id uuid REFERENCES sessions.uploads (id);
ALTER TABLE sessions.sessions ADD COLUMN input_url text;
ALTER TABLE sessions.sessions ADD CONSTRAINT sessions_one_input
    CHECK (input_upload_id IS NULL OR input_url IS NULL);

CREATE INDEX sessions_by_input_upload ON sessions.sessions (input_upload_id) WHERE input_upload_id IS NOT NULL;
