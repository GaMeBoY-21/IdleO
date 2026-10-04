-- Users and the sign-in identities linked to them (ADR 0009). The provider's
-- issuer and subject live only in identity.external_identities.
CREATE TABLE identity.users (
    id             uuid        PRIMARY KEY,
    status         text        NOT NULL CHECK (status IN ('active', 'suspended')),
    email          text,
    email_verified boolean     NOT NULL,
    display_name   text,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    version        bigint      NOT NULL
);

CREATE TABLE identity.external_identities (
    id              uuid        PRIMARY KEY,
    user_id         uuid        NOT NULL REFERENCES identity.users (id),
    issuer          text        NOT NULL,
    subject         text        NOT NULL,
    created_at      timestamptz NOT NULL,
    last_sign_in_at timestamptz NOT NULL,
    UNIQUE (issuer, subject)
);

CREATE INDEX external_identities_user_id_idx ON identity.external_identities (user_id);
