-- Following a running session (ADR 0016).

-- Checkpoints the control plane has confirmed complete: recorded only once the
-- checkpoint's _COMPLETE marker exists in storage, never on the agent's word
-- alone. object_key is the checkpoint's folder; step is its seq.
CREATE TABLE sessions.checkpoints (
    id         uuid        PRIMARY KEY,
    session_id uuid        NOT NULL REFERENCES sessions.sessions (id),
    attempt    integer     NOT NULL CHECK (attempt >= 1),
    object_key text        NOT NULL,
    step       integer     NOT NULL CHECK (step >= 1),
    created_at timestamptz NOT NULL,
    UNIQUE (session_id, attempt, step)
);

-- started_at is the session's minute 0 on the control plane's clock: the first
-- time any attempt reported RUNNING. error_code is why a FAILED session failed.
ALTER TABLE sessions.sessions ADD COLUMN started_at timestamptz;
ALTER TABLE sessions.sessions ADD COLUMN error_code text;

-- When this attempt first reported RUNNING.
ALTER TABLE sessions.attempts ADD COLUMN running_at timestamptz;
