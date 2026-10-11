-- Access by invitation (ADR 0024): a new account is 'waiting' until it enters an invite code.
-- Everyone who signed in before this stays active. Codes are stored only as a hash.
ALTER TABLE identity.users DROP CONSTRAINT users_status_check;
ALTER TABLE identity.users
    ADD CONSTRAINT users_status_check CHECK (status IN ('waiting', 'active', 'suspended'));

CREATE TABLE identity.invite_codes (
    id         uuid        PRIMARY KEY,
    code_hash  bytea       NOT NULL UNIQUE,
    note       text        NOT NULL,
    created_by text        NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz,
    used_by    uuid        REFERENCES identity.users (id),
    used_at    timestamptz,
    CHECK ((used_by IS NULL) = (used_at IS NULL))
);

-- Every code a waiting user tried, so tries are limited per hour.
CREATE TABLE identity.activation_attempts (
    id           uuid        PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES identity.users (id),
    attempted_at timestamptz NOT NULL
);

CREATE INDEX activation_attempts_user_idx ON identity.activation_attempts (user_id, attempted_at);
