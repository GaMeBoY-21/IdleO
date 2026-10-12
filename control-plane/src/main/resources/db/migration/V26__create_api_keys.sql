-- API keys (ADR 0025): the second credential beside the identity provider's ID tokens, for the CLI, the SDK and
-- scripts. A key is shown once; only its SHA-256 hash is kept, with a few of its characters for display.
CREATE TABLE identity.api_keys (
    id             uuid        PRIMARY KEY,
    user_id        uuid        NOT NULL REFERENCES identity.users (id),
    name           text        NOT NULL,
    scope          text        NOT NULL CHECK (scope IN ('read', 'full')),
    key_hash       bytea       NOT NULL UNIQUE,
    display_prefix text        NOT NULL,
    created_via    text        NOT NULL CHECK (created_via IN ('web', 'cli-login')),
    created_at     timestamptz NOT NULL,
    last_used_at   timestamptz,
    revoked_at     timestamptz
);

CREATE INDEX api_keys_user_idx ON identity.api_keys (user_id) WHERE revoked_at IS NULL;

-- Idempotency-Key records for POST /v1/api-keys, scoped to the user. A key's secret is never stored here: the
-- first answer alone carries it (IdempotentRequests, shown once).
CREATE TABLE identity.idempotency_keys (
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
