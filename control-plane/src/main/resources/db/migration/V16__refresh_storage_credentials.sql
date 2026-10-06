-- Fresh storage credentials for long jobs (ADR 0017): when an attempt's grants
-- were last minted and delivered, and whether a refresh is already queued.
ALTER TABLE sessions.attempts ADD COLUMN grants_minted_at timestamptz;
ALTER TABLE sessions.attempts ADD COLUMN refresh_queued_at timestamptz;
UPDATE sessions.attempts SET grants_minted_at = start_delivered_at;
