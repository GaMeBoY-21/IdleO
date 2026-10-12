-- Device sign-in for the CLI (ADR 0025, shaped like RFC 8628): the CLI starts one, a signed-in browser approves it
-- by its user code, and the CLI's poll then receives a new full API key, once. The user code and the poll token are
-- stored only as hashes.
CREATE TABLE identity.cli_logins (
    id              uuid        PRIMARY KEY,
    user_code_hash  bytea       NOT NULL UNIQUE,
    poll_token_hash bytea       NOT NULL,
    device_name     text        NOT NULL,
    created_at      timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL,
    approved_by     uuid        REFERENCES identity.users (id),
    approved_at     timestamptz,
    redeemed_at     timestamptz,
    api_key_id      uuid        REFERENCES identity.api_keys (id),
    CHECK ((approved_by IS NULL) = (approved_at IS NULL)),
    CHECK (redeemed_at IS NULL OR approved_at IS NOT NULL),
    CHECK ((redeemed_at IS NULL) = (api_key_id IS NULL))
);
