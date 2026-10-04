-- Hosts, their one-time enrolment codes, the certificates issued to their
-- agents, and the idempotency keys for host creation (ADR 0010). No foreign
-- key reaches into another module's schema: ownership comes from CurrentUser.
CREATE TABLE hosts.hosts (
    id            uuid        PRIMARY KEY,
    owner_user_id uuid        NOT NULL,
    name          text        NOT NULL,
    status        text        NOT NULL CHECK (status IN ('pending', 'verified', 'active', 'suspended')),
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    version       bigint      NOT NULL
);

CREATE INDEX hosts_owner_user_id_idx ON hosts.hosts (owner_user_id);

-- One live code per host; issuing a new code replaces the row. Only the
-- SHA-256 of the code is kept, never the code itself.
CREATE TABLE hosts.enrolment_codes (
    host_id    uuid        PRIMARY KEY REFERENCES hosts.hosts (id),
    code_hash  bytea       NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    used_at    timestamptz
);

-- A table rather than one fingerprint column: during renewal two certificates
-- for the same host are valid at once.
CREATE TABLE hosts.host_certificates (
    serial             numeric     PRIMARY KEY,
    host_id            uuid        NOT NULL REFERENCES hosts.hosts (id),
    fingerprint_sha256 bytea       NOT NULL UNIQUE,
    not_before         timestamptz NOT NULL,
    not_after          timestamptz NOT NULL,
    revoked_at         timestamptz,
    created_at         timestamptz NOT NULL
);

CREATE INDEX host_certificates_host_id_idx ON hosts.host_certificates (host_id);

-- Idempotency-Key records for POST /v1/hosts, scoped to the user.
CREATE TABLE hosts.idempotency_keys (
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
