-- The admin console (ADR 0027): who may use it, their second factor, their short admin sessions, and the
-- append-only audit log of every privileged action.

-- An admin, made only by the grant-admin operator command. revoke-admin deletes the row, and with it the second
-- factor, the recovery codes, the tries and the sessions below, so a later grant starts with a fresh enrolment.
-- The TOTP secret is AES-GCM ciphertext (its nonce first) under a key from the environment, never the secret
-- itself; it counts once a code from it has been confirmed. last_totp_step is the newest time step a code was
-- accepted for, so a code is never accepted twice. locked_at is set by the fifth wrong code in 15 minutes.
CREATE TABLE identity.admins (
    user_id           uuid        PRIMARY KEY REFERENCES identity.users (id),
    granted_by        text        NOT NULL CHECK (granted_by <> ''),
    granted_at        timestamptz NOT NULL,
    totp_secret       bytea,
    totp_confirmed_at timestamptz,
    last_totp_step    bigint,
    locked_at         timestamptz,
    CHECK (totp_confirmed_at IS NULL OR totp_secret IS NOT NULL)
);

-- Ten one-time recovery codes, made when the second factor is confirmed, kept only as SHA-256 hashes.
CREATE TABLE identity.admin_recovery_codes (
    id         uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES identity.admins (user_id) ON DELETE CASCADE,
    code_hash  bytea       NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    used_at    timestamptz
);

CREATE INDEX admin_recovery_codes_user_idx ON identity.admin_recovery_codes (user_id);

-- Every code tried, right or wrong, so wrong ones can be counted per 15 minutes.
CREATE TABLE identity.admin_code_attempts (
    id           uuid        PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES identity.admins (user_id) ON DELETE CASCADE,
    succeeded    boolean     NOT NULL,
    attempted_at timestamptz NOT NULL
);

CREATE INDEX admin_code_attempts_user_idx ON identity.admin_code_attempts (user_id, attempted_at);

-- An admin session: a short token, kept only as its SHA-256 hash, for the sign-in identity (issuer and subject)
-- that made it. It lasts 30 minutes from its last use, and never past ends_by.
CREATE TABLE identity.admin_sessions (
    id          uuid        PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES identity.admins (user_id) ON DELETE CASCADE,
    identity_id uuid        NOT NULL REFERENCES identity.external_identities (id),
    token_hash  bytea       NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL,
    ends_by     timestamptz NOT NULL,
    ended_at    timestamptz,
    CHECK (expires_at <= ends_by)
);

CREATE INDEX admin_sessions_user_idx ON identity.admin_sessions (user_id) WHERE ended_at IS NULL;

-- Every privileged action (technical design "Audit trail"): admin actions, operator commands, and certificate
-- issues and revocations. Written in the same transaction as the action, by identity's AuditTrail only. Who did
-- it (an admin, an operator, a host's owner or the system), what, to what, why, and the target before and after.
CREATE TABLE identity.audit_log (
    id            uuid        PRIMARY KEY,
    actor_kind    text        NOT NULL CHECK (actor_kind IN ('admin', 'operator', 'owner', 'system')),
    actor_user_id uuid,
    actor_name    text        NOT NULL CHECK (actor_name <> ''),
    action        text        NOT NULL CHECK (action ~ '^[a-z_]+(\.[a-z_]+)+$'),
    target_type   text        NOT NULL CHECK (target_type ~ '^[a-z_]+$'),
    target_id     text        NOT NULL CHECK (target_id <> ''),
    reason        text        NOT NULL CHECK (reason <> ''),
    before        jsonb,
    after         jsonb,
    created_at    timestamptz NOT NULL,
    CHECK ((actor_kind IN ('admin', 'owner')) = (actor_user_id IS NOT NULL))
);

CREATE INDEX audit_log_newest ON identity.audit_log (created_at DESC, id DESC);
CREATE INDEX audit_log_by_target ON identity.audit_log (target_type, target_id, created_at DESC);
CREATE INDEX audit_log_by_actor ON identity.audit_log (actor_user_id, created_at DESC) WHERE actor_user_id IS NOT NULL;

CREATE FUNCTION identity.refuse_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER audit_log_append_only
    BEFORE UPDATE OR DELETE ON identity.audit_log
    FOR EACH ROW EXECUTE FUNCTION identity.refuse_change();
CREATE TRIGGER audit_log_no_truncate
    BEFORE TRUNCATE ON identity.audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION identity.refuse_change();

-- And the database user the application runs as may not even ask: it keeps only reading and adding rows.
REVOKE UPDATE, DELETE, TRUNCATE ON identity.audit_log FROM PUBLIC;
REVOKE UPDATE, DELETE, TRUNCATE ON identity.audit_log FROM CURRENT_USER;
