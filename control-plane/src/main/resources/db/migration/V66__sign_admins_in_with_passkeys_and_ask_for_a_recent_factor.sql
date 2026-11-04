-- Passkeys (ADR 0051): several per admin, each named. The private key never leaves the authenticator; this keeps the
-- public key and what Spring Security's WebAuthn support needs to check each assertion.
CREATE TABLE identity.admin_passkeys (
    id                 uuid        PRIMARY KEY,
    user_id            uuid        NOT NULL REFERENCES identity.admins (user_id) ON DELETE CASCADE,
    name               text        NOT NULL CHECK (name <> ''),
    credential_id      bytea       NOT NULL UNIQUE,
    public_key         bytea       NOT NULL,
    signature_count    bigint      NOT NULL,
    uv_initialized     boolean     NOT NULL,
    backup_eligible    boolean     NOT NULL,
    backup_state       boolean     NOT NULL,
    transports         text[]      NOT NULL,
    attestation_object bytea       NOT NULL,
    client_data_json   bytea       NOT NULL,
    created_at         timestamptz NOT NULL,
    last_used_at       timestamptz
);
CREATE INDEX admin_passkeys_by_user ON identity.admin_passkeys (user_id);

-- A challenge given to a browser, used once, soon.
CREATE TABLE identity.admin_passkey_challenges (
    id         uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES identity.admins (user_id) ON DELETE CASCADE,
    kind       text        NOT NULL CHECK (kind IN ('register', 'assert')),
    challenge  bytea       NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz
);
CREATE INDEX admin_passkey_challenges_by_user ON identity.admin_passkey_challenges (user_id);

GRANT SELECT, INSERT, UPDATE, DELETE ON identity.admin_passkeys, identity.admin_passkey_challenges TO idleo_application;

-- Each admin session's record: when the last factor was given (a sensitive action needs one in the last five minutes),
-- its last use, and where it started. Nullable, so a session from the release before keeps working: it has given no
-- recent factor, and asks for one.
ALTER TABLE identity.admin_sessions
    ADD COLUMN factor_at    timestamptz,
    ADD COLUMN last_used_at timestamptz,
    ADD COLUMN ip           inet,
    ADD COLUMN user_agent   text,
    ADD COLUMN ended_by     uuid;

-- Ending anyone's session is the Owner's.
INSERT INTO identity.admin_role_permissions (role_id, permission) VALUES ('owner', 'admins.manage');
