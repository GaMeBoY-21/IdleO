-- The security log (ADR 0037): sign-ins, refused and failed sign-ins, API key and device sign-in events, admin console
-- sign-ins and wrong codes, email changes, and data export and deletion requests. The only place we keep an IP
-- address. user_id is null for a failure no account can be tied to. kind and detail are short codes. Deleted after
-- legal.retention.security-events (a year, LEGAL(confirm)) by the security-events retention rule; erasing an account
-- keeps its rows, which hold only the opaque id.
CREATE TABLE identity.security_events (
    id      uuid        PRIMARY KEY,
    user_id uuid,
    kind    text        NOT NULL CHECK (kind ~ '^[a-z_]{3,40}$'),
    detail  text        CHECK (detail ~ '^[a-z0-9_.-]{1,40}$'),
    address inet,
    at      timestamptz NOT NULL
);

CREATE INDEX security_events_at ON identity.security_events (at);
CREATE INDEX security_events_user ON identity.security_events (user_id, at) WHERE user_id IS NOT NULL;

-- When the newest ID token seen for this identity was signed in (its auth_time): a later one is a new sign-in, and
-- deleting an account needs one within the last 10 minutes.
ALTER TABLE identity.external_identities ADD COLUMN signed_in_at timestamptz;

-- An erased account (ADR 0037) keeps its row as a tombstone, its email and name cleared, so the ids that money records
-- keep resolve to "an erased account" and to nobody.
ALTER TABLE identity.users DROP CONSTRAINT users_status_check;
ALTER TABLE identity.users
    ADD CONSTRAINT users_status_check CHECK (status IN ('waiting', 'active', 'suspended', 'erased'));
ALTER TABLE identity.users
    ADD CONSTRAINT users_erased_holds_nothing CHECK (status <> 'erased' OR (email IS NULL AND display_name IS NULL));
