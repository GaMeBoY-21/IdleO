-- An upload holds a folder of files (ADR 0022): a model folder, a project, or
-- one file, a zip included. Each file has its own multipart upload in storage,
-- opened when its first part link is asked for, under
-- uploads/{upload_id}/files/{stored_path}: its relative path with every segment
-- keeping only [A-Za-z0-9._-]. path is the renter's, for display. A file of
-- zero bytes has no parts; completing it writes it empty. The upload is ready
-- once every file is complete at its declared size.
CREATE TABLE sessions.upload_files (
    upload_id    uuid        NOT NULL REFERENCES sessions.uploads (id),
    path         text        NOT NULL CHECK (octet_length(path) BETWEEN 1 AND 1024),
    stored_path  text        NOT NULL CHECK (stored_path ~ '^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$'
                                             AND stored_path !~ '(^|/)\.\.?(/|$)'),
    size_bytes   bigint      NOT NULL CHECK (size_bytes >= 0),
    object_key   text        NOT NULL UNIQUE,
    multipart_id text,
    completed_at timestamptz,
    PRIMARY KEY (upload_id, stored_path),
    UNIQUE (upload_id, path)
);

-- Every upload so far is one file, kept where it is.
INSERT INTO sessions.upload_files (upload_id, path, stored_path, size_bytes, object_key, multipart_id, completed_at)
SELECT id, filename, stored_name, size_bytes, object_key, multipart_id, completed_at
FROM sessions.uploads;

ALTER TABLE sessions.uploads RENAME COLUMN filename TO name;
ALTER TABLE sessions.uploads ADD COLUMN file_count integer;
UPDATE sessions.uploads SET file_count = 1;
ALTER TABLE sessions.uploads ALTER COLUMN file_count SET NOT NULL;
ALTER TABLE sessions.uploads ADD CONSTRAINT uploads_file_count CHECK (file_count >= 1);
ALTER TABLE sessions.uploads DROP CONSTRAINT uploads_size_bytes_check;
ALTER TABLE sessions.uploads ADD CONSTRAINT uploads_size_bytes CHECK (size_bytes >= 0);
-- Their multipart uploads and keys are the files' now; the check that a
-- pending upload has a multipart upload goes with the column.
ALTER TABLE sessions.uploads DROP COLUMN multipart_id;
ALTER TABLE sessions.uploads DROP COLUMN object_key;
ALTER TABLE sessions.uploads DROP COLUMN stored_name;

-- When a session last took it as an input. A ready upload expires 30 days
-- after that, or after it was completed if no session has used it.
ALTER TABLE sessions.uploads ADD COLUMN last_used_at timestamptz;
