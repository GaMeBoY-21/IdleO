-- Settlement (ADR 0017): holds that move with the job, and settled minutes.

-- A hold's amount now changes over a session's life: it shrinks by what each
-- settlement charges and is topped back up to 30 minutes ahead. It can reach
-- zero. committed_paise is what the session has used but not yet settled, as
-- metering last counted it; the lease is renewed only while the hold minus that
-- covers the lease.
ALTER TABLE billing.reservations DROP CONSTRAINT reservations_amount_paise_check;
ALTER TABLE billing.reservations ADD CONSTRAINT reservations_amount_not_negative CHECK (amount_paise >= 0);
ALTER TABLE billing.reservations ADD COLUMN committed_paise bigint NOT NULL DEFAULT 0
    CHECK (committed_paise >= 0);

-- An active hold may change its amount and committed use, or be released once;
-- a released hold never changes again, and nothing else ever does.
CREATE OR REPLACE FUNCTION billing.guard_reservation_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'active' AND NEW.id = OLD.id AND NEW.user_id = OLD.user_id
            AND NEW.session_id = OLD.session_id AND NEW.created_at = OLD.created_at
            AND (NEW.status = 'active' OR (NEW.status = 'released' AND NEW.amount_paise = OLD.amount_paise)) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'billing.reservations only allows changing an active hold''s amount, or releasing it'
        USING ERRCODE = 'restrict_violation';
END;
$$;

-- Every change to a hold's amount, append-only: reserved, settled (with the
-- settlement's ledger transaction), topped up, released. For each hold, the
-- changes add up to its amount while active, and to zero once released; the
-- nightly reconciliation checks it.
CREATE TABLE billing.reservation_changes (
    id             uuid        PRIMARY KEY,
    reservation_id uuid        NOT NULL REFERENCES billing.reservations (id),
    session_id     uuid        NOT NULL,
    reason         text        NOT NULL CHECK (reason IN ('reserved', 'settled', 'topped_up', 'released')),
    before_paise   bigint      NOT NULL CHECK (before_paise >= 0),
    after_paise    bigint      NOT NULL CHECK (after_paise >= 0),
    txn_id         uuid,
    created_at     timestamptz NOT NULL,
    CHECK ((reason = 'settled') = (txn_id IS NOT NULL))
);

CREATE INDEX reservation_changes_by_reservation ON billing.reservation_changes (reservation_id, created_at);

CREATE TRIGGER reservation_changes_append_only
    BEFORE UPDATE OR DELETE ON billing.reservation_changes
    FOR EACH ROW EXECUTE FUNCTION billing.refuse_change();
CREATE TRIGGER reservation_changes_no_truncate
    BEFORE TRUNCATE ON billing.reservation_changes
    FOR EACH STATEMENT EXECUTE FUNCTION billing.refuse_change();

-- The history of holds made before this migration: reserved, and released if so.
INSERT INTO billing.reservation_changes (id, reservation_id, session_id, reason, before_paise, after_paise, created_at)
SELECT gen_random_uuid(), id, session_id, 'reserved', 0, amount_paise, created_at FROM billing.reservations;
INSERT INTO billing.reservation_changes (id, reservation_id, session_id, reason, before_paise, after_paise, created_at)
SELECT gen_random_uuid(), id, session_id, 'released', amount_paise, 0, released_at
FROM billing.reservations WHERE status = 'released';

-- Every settlement of a session's minutes: one ledger transaction each, the
-- session's n-th window. A minute records the settlement that charged it, so a
-- later refund reverses it in that settlement's proportions.
CREATE TABLE metering.settlements (
    txn_id           uuid        PRIMARY KEY,
    session_id       uuid        NOT NULL REFERENCES metering.metered_sessions (session_id),
    window_number    integer     NOT NULL CHECK (window_number >= 1),
    window_end       timestamptz,
    minutes          integer     NOT NULL CHECK (minutes >= 1),
    amount_paise     bigint      NOT NULL CHECK (amount_paise > 0),
    host_share_paise bigint      NOT NULL CHECK (host_share_paise >= 0),
    commission_paise bigint      NOT NULL CHECK (commission_paise >= 0),
    settled_at       timestamptz NOT NULL,
    UNIQUE (session_id, window_number),
    CHECK (host_share_paise + commission_paise = amount_paise)
);

ALTER TABLE metering.usage_minutes ADD COLUMN settlement_txn uuid;

ALTER TABLE metering.metered_sessions ADD COLUMN settlements integer NOT NULL DEFAULT 0;
ALTER TABLE metering.metered_sessions ADD COLUMN next_window_at timestamptz;
UPDATE metering.metered_sessions SET next_window_at = started_at + interval '15 minutes';
ALTER TABLE metering.metered_sessions ALTER COLUMN next_window_at SET NOT NULL;
-- Credit: low_credit when the hold plus the renter's available balance covers
-- under 10 minutes; stopped_for when the session was stopped for credit.
ALTER TABLE metering.metered_sessions ADD COLUMN low_credit boolean NOT NULL DEFAULT false;
ALTER TABLE metering.metered_sessions ADD COLUMN stopped_for text
    CHECK (stopped_for IN ('credit_exhausted', 'max_spend_reached'));

-- Minutes both witnesses saw but the spending cap or the renter's credit
-- couldn't pay for are never charged either: they go to review too.
ALTER TABLE metering.review_minutes DROP CONSTRAINT review_minutes_reason_check;
ALTER TABLE metering.review_minutes ADD CONSTRAINT review_minutes_reason_check
    CHECK (reason IN ('agent_only', 'lease_only', 'late_report', 'over_limit'));
