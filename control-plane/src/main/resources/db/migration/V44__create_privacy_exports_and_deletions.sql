-- A person's own data (ADR 0037): their exports, their requests to delete the account, and the list of accounts
-- erased, which a restore of the database reads to erase them again.
CREATE SCHEMA IF NOT EXISTS privacy;

-- "Download my data": one export at a time and one a day, made by a job into a zip in storage at exports/{id}/, which
-- is deleted seven days after it's ready (the exports retention rule).
CREATE TABLE privacy.exports (
    id           uuid        PRIMARY KEY,
    user_id      uuid        NOT NULL,
    state        text        NOT NULL CHECK (state IN ('requested', 'ready', 'failed', 'expired')),
    requested_at timestamptz NOT NULL,
    ready_at     timestamptz,
    expires_at   timestamptz,
    size_bytes   bigint,
    attempts     integer     NOT NULL DEFAULT 0,
    CHECK ((state = 'ready') <= (ready_at IS NOT NULL AND expires_at IS NOT NULL))
);

CREATE UNIQUE INDEX exports_one_at_a_time ON privacy.exports (user_id) WHERE state = 'requested';
CREATE INDEX exports_by_user ON privacy.exports (user_id, requested_at DESC);

-- A request to delete an account. awaiting_confirmation until the emailed link (single-use, 24 hours, kept as its
-- SHA-256 only) is opened; scheduled for delete_after, seven days later (legal.erasure.waiting-period), until then
-- cancelled with one click; then erasing, step by step (step is the last one done), and erased. address and
-- identities are what the erasure needs after the account's own rows are gone (the last email, and the sign-in
-- provider's accounts to delete), kept only while it runs.
CREATE TABLE privacy.deletion_requests (
    id                 uuid        PRIMARY KEY,
    user_id            uuid        NOT NULL,
    state              text        NOT NULL CHECK (state IN (
                                       'awaiting_confirmation', 'scheduled', 'cancelled', 'erasing', 'erased',
                                       'expired')),
    legal_order        boolean     NOT NULL DEFAULT false,
    requested_at       timestamptz NOT NULL,
    confirm_token_hash bytea       CHECK (length(confirm_token_hash) = 32),
    confirm_expires_at timestamptz NOT NULL,
    confirmed_at       timestamptz,
    delete_after       timestamptz,
    reminded_at        timestamptz,
    cancelled_at       timestamptz,
    erasing_since      timestamptz,
    step               integer     NOT NULL DEFAULT 0,
    address            text,
    identities         jsonb,
    erased_at          timestamptz,
    CHECK ((state = 'scheduled') <= (delete_after IS NOT NULL)),
    CHECK ((state = 'erased') <= (address IS NULL AND identities IS NULL AND erased_at IS NOT NULL))
);

CREATE UNIQUE INDEX deletion_requests_one_open ON privacy.deletion_requests (user_id)
    WHERE state IN ('awaiting_confirmation', 'scheduled', 'erasing');
CREATE INDEX deletion_requests_due ON privacy.deletion_requests (delete_after) WHERE state IN ('scheduled', 'erasing');

-- The one-click cancel links sent while a deletion is scheduled (its first email and the reminder), each kept as its
-- SHA-256 only; any of them cancels it.
CREATE TABLE privacy.cancel_links (
    token_hash bytea       PRIMARY KEY CHECK (length(token_hash) = 32),
    request_id uuid        NOT NULL REFERENCES privacy.deletion_requests (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL
);

-- Every account erased, by its opaque id only, never changed or deleted: after a restore of the database,
-- ops/backup/restore.sh runs reapply-erasures, which erases each of them again.
CREATE TABLE privacy.erased_users (
    user_id   uuid        PRIMARY KEY,
    erased_at timestamptz NOT NULL
);

CREATE FUNCTION privacy.erased_users_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'privacy.erased_users is append-only';
END;
$$;

CREATE TRIGGER erased_users_no_update BEFORE UPDATE OR DELETE ON privacy.erased_users
    FOR EACH ROW EXECUTE FUNCTION privacy.erased_users_append_only();
CREATE TRIGGER erased_users_no_truncate BEFORE TRUNCATE ON privacy.erased_users
    FOR EACH STATEMENT EXECUTE FUNCTION privacy.erased_users_append_only();

-- What erasure clears that was required before: a rating keeps its stars without its author, and the invite code an
-- erased person used keeps no note about them.
ALTER TABLE trust.ratings ALTER COLUMN renter_id DROP NOT NULL;
ALTER TABLE identity.invite_codes ALTER COLUMN note DROP NOT NULL;

REVOKE UPDATE, DELETE, TRUNCATE ON privacy.erased_users FROM PUBLIC;
REVOKE UPDATE, DELETE, TRUNCATE ON privacy.erased_users FROM CURRENT_USER;
