-- Ratings and reports (ADR 0028). The trust module keeps its own record of each session that ended, from the
-- sessions module's events, so it never reads that module's tables: whose it was, the host that ran its last attempt,
-- and when it ran and ended.
CREATE TABLE trust.ended_sessions (
    session_id  uuid        PRIMARY KEY,
    renter_id   uuid        NOT NULL,
    host_id     uuid,
    state       text        NOT NULL,
    started_at  timestamptz,
    ended_at    timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    CONSTRAINT ended_sessions_ran_on_a_host CHECK ((started_at IS NULL) = (host_id IS NULL))
);

-- One rating a session, from its renter, for the host that ran its last attempt. Changed in place within 7 days of
-- being given; only admins ever read the comment.
CREATE TABLE trust.ratings (
    session_id uuid        PRIMARY KEY REFERENCES trust.ended_sessions (session_id),
    renter_id  uuid        NOT NULL,
    host_id    uuid        NOT NULL,
    stars      smallint    NOT NULL CHECK (stars BETWEEN 1 AND 5),
    comment    text        CHECK (char_length(comment) BETWEEN 1 AND 500),
    rated_at   timestamptz NOT NULL,
    changed_at timestamptz NOT NULL
);

CREATE INDEX ratings_by_host ON trust.ratings (host_id);

-- A renter's report of a problem with a session. At most one is open a session; an admin resolves it once, with a
-- refund or without, and a reason the renter reads. A refund's ledger transaction and its split are kept beside it.
CREATE TABLE trust.disputes (
    id                      uuid        PRIMARY KEY,
    session_id              uuid        NOT NULL REFERENCES trust.ended_sessions (session_id),
    renter_id               uuid        NOT NULL,
    reason                  text        NOT NULL CHECK (reason IN (
                                            'didnt_start', 'failed', 'slow', 'wrong_output', 'charged_wrong', 'other')),
    details                 text        CHECK (char_length(details) BETWEEN 1 AND 2000),
    state                   text        NOT NULL CHECK (state IN ('open', 'refunded', 'not_refunded')),
    created_at              timestamptz NOT NULL,
    resolution_reason       text,
    resolved_by             text,
    resolved_at             timestamptz,
    refund_paise            bigint      CHECK (refund_paise > 0),
    commission_refund_paise bigint      CHECK (commission_refund_paise >= 0),
    host_refund_paise       bigint      CHECK (host_refund_paise >= 0),
    refund_txn              uuid,
    CONSTRAINT disputes_resolved_together CHECK (
        (state = 'open') = (resolved_at IS NULL)
        AND (resolved_at IS NULL) = (resolved_by IS NULL)
        AND (resolved_at IS NULL) = (resolution_reason IS NULL)),
    CONSTRAINT disputes_refund_recorded CHECK (
        (state = 'refunded') = (refund_paise IS NOT NULL)
        AND (refund_paise IS NULL) = (refund_txn IS NULL)
        AND (refund_paise IS NULL) = (commission_refund_paise IS NULL)
        AND (refund_paise IS NULL) = (host_refund_paise IS NULL)
        AND refund_paise = commission_refund_paise + host_refund_paise)
);

CREATE UNIQUE INDEX disputes_one_open_a_session ON trust.disputes (session_id) WHERE state = 'open';
CREATE INDEX disputes_by_renter ON trust.disputes (renter_id, id);
CREATE INDEX disputes_queue ON trust.disputes ((state <> 'open'), id);

-- A report is resolved once and never deleted.
CREATE FUNCTION trust.guard_dispute() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.state = 'open' AND NEW.state <> 'open' AND NEW.id = OLD.id
            AND NEW.session_id = OLD.session_id AND NEW.renter_id = OLD.renter_id AND NEW.reason = OLD.reason
            AND NEW.details IS NOT DISTINCT FROM OLD.details AND NEW.created_at = OLD.created_at THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'trust.disputes only allows resolving an open report once'
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER disputes_resolved_once
    BEFORE UPDATE ON trust.disputes
    FOR EACH ROW EXECUTE FUNCTION trust.guard_dispute();
CREATE TRIGGER disputes_never_deleted
    BEFORE DELETE ON trust.disputes
    FOR EACH ROW EXECUTE FUNCTION trust.guard_dispute();

-- Idempotency-Key records for POST /v1/disputes, scoped to the user.
CREATE TABLE trust.idempotency_keys (
    user_id           uuid        NOT NULL,
    idempotency_key   text        NOT NULL,
    request_hash      bytea       NOT NULL,
    state             text        NOT NULL CHECK (state IN ('in_progress', 'completed')),
    response_status   integer,
    response_location text,
    response_body     text,
    locked_at         timestamptz NOT NULL,
    created_at        timestamptz NOT NULL,
    expires_at        timestamptz NOT NULL,
    PRIMARY KEY (user_id, idempotency_key)
);

-- A session's host earnings are held from payout while a report about it is open (ADR 0028). Payouts don't exist
-- yet; when they do, they skip what's held here.
CREATE TABLE billing.payout_holds (
    dispute_id  uuid        PRIMARY KEY,
    session_id  uuid        NOT NULL,
    held_at     timestamptz NOT NULL,
    released_at timestamptz,
    CONSTRAINT payout_holds_released_after_held CHECK (released_at IS NULL OR released_at >= held_at)
);

CREATE INDEX payout_holds_open ON billing.payout_holds (session_id) WHERE released_at IS NULL;
