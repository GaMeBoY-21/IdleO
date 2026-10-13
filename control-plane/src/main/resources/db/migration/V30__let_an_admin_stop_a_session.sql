-- An admin may force-stop a running session from the console (ADR 0027): its attempt is stopped for
-- 'operator_stopped', beside a renter's stop and the two credit stops.
ALTER TABLE sessions.attempts DROP CONSTRAINT attempts_stop_reason_check;
ALTER TABLE sessions.attempts ADD CONSTRAINT attempts_stop_reason_check
    CHECK (stop_reason IN ('renter_stopped', 'credit_exhausted', 'max_spend_reached', 'operator_stopped'));
