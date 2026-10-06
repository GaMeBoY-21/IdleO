-- Stopping a running attempt (ADR 0017): by its renter, or because its credit
-- ran out or it reached its spending cap. The agent gets a StopSession with a
-- checkpoint first and a grace period; the session completes when the agent
-- reports STOPPED, or once stop_deadline has passed, when its lease has ended
-- for certain.
ALTER TABLE sessions.attempts ADD COLUMN stop_requested_at timestamptz;
ALTER TABLE sessions.attempts ADD COLUMN stop_reason text
    CHECK (stop_reason IN ('renter_stopped', 'credit_exhausted', 'max_spend_reached'));
ALTER TABLE sessions.attempts ADD COLUMN stop_deadline timestamptz;
ALTER TABLE sessions.attempts ADD CONSTRAINT attempts_stop_complete
    CHECK ((stop_requested_at IS NULL) = (stop_reason IS NULL) AND (stop_reason IS NULL) = (stop_deadline IS NULL));
