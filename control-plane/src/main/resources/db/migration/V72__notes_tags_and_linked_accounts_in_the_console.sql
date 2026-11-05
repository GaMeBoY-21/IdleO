-- Notes and tags on a user, host, session or payment, in the console's own schema (ADR 0052). subject_user_id is the
-- person it's about, asked of the module that owns the record: a note about them is their personal data.
CREATE SCHEMA IF NOT EXISTS console;
GRANT USAGE ON SCHEMA console TO idleo_application;

CREATE TABLE console.notes (
    id              uuid        PRIMARY KEY,
    subject_kind    text        NOT NULL CHECK (subject_kind IN ('user', 'host', 'session', 'payment')),
    subject_id      uuid        NOT NULL,
    subject_user_id uuid        NOT NULL,
    author_id       uuid        NOT NULL,
    body            text        NOT NULL CHECK (length(body) BETWEEN 1 AND 4000),
    pinned          boolean     NOT NULL DEFAULT false,
    -- A note written for a fraud investigation that's still open stays out of the person's export. LEGAL(confirm)
    open_investigation boolean  NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL,
    edited_at       timestamptz,
    version         bigint      NOT NULL DEFAULT 1
);
CREATE INDEX notes_by_subject ON console.notes (subject_kind, subject_id, created_at DESC);
CREATE INDEX notes_by_person ON console.notes (subject_user_id);
GRANT SELECT, INSERT, UPDATE, DELETE ON console.notes TO idleo_application;

-- The Owner's vocabulary. A colour is a name from the design system, never a value.
CREATE TABLE console.tags (
    id         text        PRIMARY KEY CHECK (id ~ '^[a-z0-9-]{1,40}$'),
    label      text        NOT NULL CHECK (length(label) BETWEEN 1 AND 40),
    colour     text        NOT NULL CHECK (colour IN ('plain', 'running', 'danger')),
    created_at timestamptz NOT NULL
);
GRANT SELECT, INSERT, UPDATE, DELETE ON console.tags TO idleo_application;
INSERT INTO console.tags (id, label, colour, created_at) VALUES
    ('vip', 'VIP', 'running', now()),
    ('fraud-suspect', 'Fraud suspect', 'danger', now()),
    ('partner', 'Partner', 'plain', now());

CREATE TABLE console.tag_assignments (
    subject_kind    text        NOT NULL CHECK (subject_kind IN ('user', 'host', 'session', 'payment')),
    subject_id      uuid        NOT NULL,
    tag_id          text        NOT NULL REFERENCES console.tags (id) ON DELETE CASCADE,
    subject_user_id uuid        NOT NULL,
    added_by        uuid        NOT NULL,
    added_at        timestamptz NOT NULL,
    PRIMARY KEY (subject_kind, subject_id, tag_id)
);
CREATE INDEX tag_assignments_by_tag ON console.tag_assignments (tag_id, subject_kind);
CREATE INDEX tag_assignments_by_person ON console.tag_assignments (subject_user_id);
GRANT SELECT, INSERT, UPDATE, DELETE ON console.tag_assignments TO idleo_application;

-- Who may do it (ADR 0051). Reading notes is for everyone who reads the records; the Auditor only reads.
INSERT INTO identity.admin_role_permissions (role_id, permission)
SELECT role_id, 'notes.read' FROM unnest(ARRAY['owner', 'operations', 'support', 'trust_safety', 'finance', 'auditor']) AS role_id;
INSERT INTO identity.admin_role_permissions (role_id, permission)
SELECT role_id, 'notes.write' FROM unnest(ARRAY['owner', 'operations', 'support', 'trust_safety', 'finance']) AS role_id;
INSERT INTO identity.admin_role_permissions (role_id, permission) VALUES ('owner', 'tags.manage');
INSERT INTO identity.admin_role_permissions (role_id, permission)
SELECT role_id, 'trust.read' FROM unnest(ARRAY['owner', 'trust_safety']) AS role_id;

-- Linked accounts (ADR 0052): the same device is the same browser from the same address, and an email is compared once
-- its dots and +tags are gone (Gmail's rule, and +tags for everyone). Neither is shown: each only finds who else matches.
ALTER TABLE identity.security_events ADD COLUMN device text;
CREATE INDEX security_events_by_device ON identity.security_events (device, address) WHERE device IS NOT NULL;
CREATE INDEX security_events_by_address ON identity.security_events (address, at) WHERE address IS NOT NULL;
ALTER TABLE identity.users ADD COLUMN email_canonical text GENERATED ALWAYS AS (
    CASE
        WHEN lower(split_part(email, '@', 2)) IN ('gmail.com', 'googlemail.com')
            THEN replace(split_part(lower(split_part(email, '@', 1)), '+', 1), '.', '') || '@gmail.com'
        WHEN email IS NOT NULL
            THEN split_part(lower(split_part(email, '@', 1)), '+', 1) || '@' || lower(split_part(email, '@', 2))
    END
) STORED;
CREATE INDEX users_by_canonical_email ON identity.users (email_canonical) WHERE email_canonical IS NOT NULL;
