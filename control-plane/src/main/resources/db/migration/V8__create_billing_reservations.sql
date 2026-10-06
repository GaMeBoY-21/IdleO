-- Credit held for booked sessions (ADR 0015). A hold isn't a movement of money,
-- so it's not a ledger entry; wallets.reserved_paise always equals the sum of
-- the user's active holds, changed in the same transaction. A session can be
-- re-matched after a host declines, so it may have several holds over time, but
-- never more than one active at once.
CREATE TABLE billing.reservations (
    id           uuid        PRIMARY KEY,
    user_id      uuid        NOT NULL,
    session_id   uuid        NOT NULL,
    amount_paise bigint      NOT NULL CHECK (amount_paise > 0),
    status       text        NOT NULL CHECK (status IN ('active', 'released')),
    created_at   timestamptz NOT NULL,
    released_at  timestamptz,
    CONSTRAINT reservations_released_at CHECK ((status = 'released') = (released_at IS NOT NULL))
);

CREATE UNIQUE INDEX reservations_one_active_per_session ON billing.reservations (session_id)
    WHERE status = 'active';
CREATE INDEX reservations_active_by_user ON billing.reservations (user_id)
    WHERE status = 'active';

-- Append-only, except that an active hold may be released, once.
CREATE FUNCTION billing.guard_reservation_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'active' AND NEW.status = 'released'
            AND NEW.id = OLD.id AND NEW.user_id = OLD.user_id AND NEW.session_id = OLD.session_id
            AND NEW.amount_paise = OLD.amount_paise AND NEW.created_at = OLD.created_at THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'billing.reservations only allows releasing an active hold'
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER reservations_release_only
    BEFORE UPDATE ON billing.reservations
    FOR EACH ROW EXECUTE FUNCTION billing.guard_reservation_change();
CREATE TRIGGER reservations_no_delete
    BEFORE DELETE ON billing.reservations
    FOR EACH ROW EXECUTE FUNCTION billing.refuse_change();
CREATE TRIGGER reservations_no_truncate
    BEFORE TRUNCATE ON billing.reservations
    FOR EACH STATEMENT EXECUTE FUNCTION billing.refuse_change();
