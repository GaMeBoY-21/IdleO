-- When a session's checkpoints, and then the rest of its objects (outputs and logs), were deleted from storage by the
-- checkpoints and outputs retention rules (ADR 0037): each session's are deleted once, and the rules don't list
-- storage for sessions already done.
ALTER TABLE sessions.sessions ADD COLUMN checkpoints_deleted_at timestamptz;
ALTER TABLE sessions.sessions ADD COLUMN files_deleted_at timestamptz;

CREATE INDEX sessions_files_kept ON sessions.sessions (state_changed_at) WHERE files_deleted_at IS NULL;
