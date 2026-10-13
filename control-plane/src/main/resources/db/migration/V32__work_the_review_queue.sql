-- The review queue (ADR 0017, ADR 0027): an admin charges or waives each minute only one witness saw, or that the
-- spending cap or the renter's credit couldn't pay for, with a reason. A charged minute is its own settlement, whose
-- ledger transaction it names; a waived one moves no money. A minute is decided once.
ALTER TABLE metering.review_minutes
    ADD COLUMN resolution        text CHECK (resolution IN ('charged', 'waived')),
    ADD COLUMN resolution_reason text,
    ADD COLUMN resolved_by       text,
    ADD COLUMN resolved_at       timestamptz,
    ADD COLUMN settlement_txn    uuid REFERENCES metering.settlements (txn_id),
    ADD CONSTRAINT review_minutes_resolved_together CHECK (
        (resolution IS NULL) = (resolved_at IS NULL)
        AND (resolution IS NULL) = (resolved_by IS NULL)
        AND (resolution IS NULL) = (resolution_reason IS NULL)),
    ADD CONSTRAINT review_minutes_charged_settlement CHECK ((resolution = 'charged') = (settlement_txn IS NOT NULL));

-- A decided minute never changes again.
CREATE FUNCTION metering.guard_review_minute() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.resolution IS NULL AND NEW.session_id = OLD.session_id AND NEW.minute_index = OLD.minute_index
            AND NEW.minute_at = OLD.minute_at AND NEW.attempt IS NOT DISTINCT FROM OLD.attempt
            AND NEW.reason = OLD.reason AND NEW.created_at = OLD.created_at THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'metering.review_minutes only allows deciding a minute once'
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER review_minutes_decided_once
    BEFORE UPDATE ON metering.review_minutes
    FOR EACH ROW EXECUTE FUNCTION metering.guard_review_minute();
CREATE TRIGGER review_minutes_never_deleted
    BEFORE DELETE ON metering.review_minutes
    FOR EACH ROW EXECUTE FUNCTION metering.guard_review_minute();

CREATE INDEX review_minutes_open ON metering.review_minutes (created_at) WHERE resolution IS NULL;
