-- Sessions and their audit trail (technical design lines 132-152, ADR 0015).
-- renter_id and host_id are other modules' ids, with no foreign keys across
-- modules. price_paise_per_min is copied from the host's listing when the
-- session is matched, so a later price change never changes a booked session.
CREATE TABLE sessions.sessions (
    id                      uuid        PRIMARY KEY,
    renter_id               uuid        NOT NULL,
    host_id                 uuid,
    type                    text        NOT NULL CHECK (type IN ('game', 'batch', 'endpoint', 'notebook')),
    state                   text        NOT NULL CHECK (state IN (
                                'REQUESTED', 'MATCHED', 'PROVISIONING', 'RUNNING', 'HOST_LOST', 'FINISHING',
                                'CANCELLED', 'NO_HOST_FREE', 'FAILED', 'COMPLETED', 'ENDED_REFUNDED')),
    attempt                 integer     NOT NULL CHECK (attempt >= 1),
    template_id             text        NOT NULL,
    image_digest            text        NOT NULL CHECK (image_digest LIKE '%@sha256:%'),
    vram_needed_mb          integer     NOT NULL CHECK (vram_needed_mb > 0),
    max_price_paise_per_min integer     NOT NULL CHECK (max_price_paise_per_min > 0),
    price_paise_per_min     integer     CHECK (price_paise_per_min > 0),
    max_spend_paise         bigint      NOT NULL CHECK (max_spend_paise > 0),
    max_runtime_seconds     integer     NOT NULL CHECK (max_runtime_seconds > 0),
    lease_expires_at        timestamptz,
    idempotency_key         text        NOT NULL,
    request_hash            text        NOT NULL,
    state_changed_at        timestamptz NOT NULL,
    version                 integer     NOT NULL,
    created_at              timestamptz NOT NULL,
    UNIQUE (renter_id, idempotency_key)
);

-- At most one live session per host: the second guard against double booking,
-- behind the scheduler's row lock (technical design line 150).
CREATE UNIQUE INDEX one_live_session_per_host ON sessions.sessions (host_id)
    WHERE state IN ('MATCHED', 'PROVISIONING', 'RUNNING', 'FINISHING');

CREATE INDEX sessions_by_renter ON sessions.sessions (renter_id, created_at DESC);
CREATE INDEX sessions_by_state ON sessions.sessions (state, state_changed_at);

-- Every transition, append-only.
CREATE TABLE sessions.session_events (
    id         uuid        PRIMARY KEY,
    session_id uuid        NOT NULL REFERENCES sessions.sessions (id),
    from_state text,
    to_state   text        NOT NULL,
    reason     text        NOT NULL,
    at         timestamptz NOT NULL
);

CREATE INDEX session_events_by_session ON sessions.session_events (session_id, at);

CREATE FUNCTION sessions.refuse_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER session_events_append_only
    BEFORE UPDATE OR DELETE ON sessions.session_events
    FOR EACH ROW EXECUTE FUNCTION sessions.refuse_change();
CREATE TRIGGER session_events_no_truncate
    BEFORE TRUNCATE ON sessions.session_events
    FOR EACH STATEMENT EXECUTE FUNCTION sessions.refuse_change();
