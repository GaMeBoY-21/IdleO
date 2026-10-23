-- Reports on a session (ADR 0028, ADR 0039): a renter may say the host stopped it, and an admin resolving a report may
-- flag the session's host. A flag is a mark on the host's record that admins see, kept for the host score to use; the
-- host never sees it, and nothing in it names the renter.

ALTER TABLE trust.disputes DROP CONSTRAINT disputes_reason_check;
ALTER TABLE trust.disputes ADD CONSTRAINT disputes_reason_check CHECK (reason IN (
    'didnt_start', 'failed', 'slow', 'host_stopped', 'wrong_output', 'charged_wrong', 'other'));

CREATE TABLE trust.host_flags (
    id         uuid        PRIMARY KEY,
    host_id    uuid        NOT NULL,
    -- The report it came from: one flag a report.
    dispute_id uuid        NOT NULL UNIQUE REFERENCES trust.disputes (id),
    reason     text        NOT NULL CHECK (char_length(reason) BETWEEN 5 AND 500),
    flagged_by text        NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX host_flags_by_host ON trust.host_flags (host_id, created_at DESC);
