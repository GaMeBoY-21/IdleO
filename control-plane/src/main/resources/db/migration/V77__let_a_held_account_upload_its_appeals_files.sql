-- An upload made to attach to an appeal (ADR 0053): the one kind a suspended or banned account may make.
CREATE TABLE sessions.appeal_uploads (
    upload_id  uuid        PRIMARY KEY REFERENCES sessions.uploads (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX appeal_uploads_by_user ON sessions.appeal_uploads (user_id, created_at);
GRANT SELECT, INSERT, DELETE ON sessions.appeal_uploads TO idleo_application;
