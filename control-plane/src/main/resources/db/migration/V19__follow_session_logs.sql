-- Session logs (ADR 0019): each attempt's highest log chunk that has finished
-- uploading to sessions/{id}/attempt-{n}/logs/{seq}.log, as its agent reports
-- it in heartbeats and statuses. Only ever raised, so the control plane reads
-- only chunks it knows exist and never lists storage.
ALTER TABLE sessions.attempts ADD COLUMN latest_log_seq integer NOT NULL DEFAULT 0 CHECK (latest_log_seq >= 0);

-- The session list pages newest first by id (UUIDv7).
CREATE INDEX sessions_by_renter_id ON sessions.sessions (renter_id, id DESC);
