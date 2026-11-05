-- The enforcement ladder and appeals (ADR 0053). A template's clause is LEGAL(confirm): a lawyer checks each against the
-- Terms before they're relied on.
CREATE TABLE trust.enforcement_templates (
    id      text PRIMARY KEY,
    role    text NOT NULL CHECK (role IN ('renter', 'host')),
    step    text NOT NULL CHECK (step IN ('warn', 'limit', 'suspend', 'ban')),
    title   text NOT NULL,
    body    text NOT NULL,
    clause  text NOT NULL
);
INSERT INTO trust.enforcement_templates (id, role, step, title, body, clause) VALUES
    ('renter-warn', 'renter', 'warn', 'A warning about how you use IdleO',
     'What you ran broke our rules. Nothing has changed on your account this time, but it counts against it for a while.',
     'The Terms, on what you may run'),
    ('renter-limit', 'renter', 'limit', 'A limit on your account',
     'What you ran broke our rules, so part of your account is limited for now. The rest works as before.',
     'The Terms, on what you may run'),
    ('renter-suspend', 'renter', 'suspend', 'Your account is suspended',
     'What you ran broke our rules, so your account is suspended until the date below. Your files and credit stay as they are.',
     'The Terms, on suspending accounts'),
    ('renter-ban', 'renter', 'ban', 'Your account is closed',
     'What you ran broke our rules badly or repeatedly, so your account is closed.',
     'The Terms, on closing accounts'),
    ('host-warn', 'host', 'warn', 'A warning about your machine',
     'How your machine or your sessions ran broke our rules for hosts. Nothing has changed this time, but it counts against you for a while.',
     'The Terms for hosts, on running sessions'),
    ('host-limit', 'host', 'limit', 'A limit on your hosting',
     'How your machine or your sessions ran broke our rules for hosts, so part of your hosting is limited for now.',
     'The Terms for hosts, on running sessions'),
    ('host-suspend', 'host', 'suspend', 'Your hosting is suspended',
     'How your machine or your sessions ran broke our rules for hosts, so your account is suspended until the date below.',
     'The Terms for hosts, on suspending hosts'),
    ('host-ban', 'host', 'ban', 'Your account is closed',
     'How your machine or your sessions ran broke our rules for hosts badly or repeatedly, so your account is closed.',
     'The Terms for hosts, on closing accounts');
GRANT SELECT, INSERT ON trust.enforcement_templates TO idleo_application;

CREATE TABLE trust.enforcements (
    id                uuid        PRIMARY KEY,
    user_id           uuid        NOT NULL,
    role              text        NOT NULL CHECK (role IN ('renter', 'host')),
    step              text        NOT NULL CHECK (step IN ('warn', 'limit', 'suspend', 'ban')),
    template_id       text        NOT NULL REFERENCES trust.enforcement_templates (id),
    limit_kind        text        CHECK (limit_kind IN ('no-custom-images', 'session-hours-cap', 'payouts-held')),
    limit_hours       integer     CHECK (limit_hours > 0),
    ends_at           timestamptz,
    strike_expires_at timestamptz NOT NULL,
    state             text        NOT NULL CHECK (state IN ('active', 'ended', 'lifted')),
    reason            text,
    enforced_by       uuid        NOT NULL,
    created_at        timestamptz NOT NULL,
    ended_at          timestamptz,
    version           bigint      NOT NULL DEFAULT 1,
    CHECK ((step = 'limit') = (limit_kind IS NOT NULL)),
    CHECK (step <> 'suspend' OR ends_at IS NOT NULL)
);
CREATE INDEX enforcements_by_user ON trust.enforcements (user_id, created_at DESC);
CREATE INDEX enforcements_ending ON trust.enforcements (ends_at) WHERE state = 'active' AND ends_at IS NOT NULL;
CREATE TRIGGER enforcements_version BEFORE UPDATE ON trust.enforcements
    FOR EACH ROW EXECUTE FUNCTION identity.bump_version();
GRANT SELECT, INSERT, UPDATE ON trust.enforcements TO idleo_application;

CREATE TABLE trust.appeals (
    id              uuid        PRIMARY KEY,
    enforcement_id  uuid        NOT NULL UNIQUE REFERENCES trust.enforcements (id),
    user_id         uuid        NOT NULL,
    body            text        CHECK (char_length(body) <= 5000),
    attachments     uuid[]      NOT NULL DEFAULT '{}' CHECK (cardinality(attachments) <= 3),
    state           text        NOT NULL CHECK (state IN ('open', 'upheld', 'reversed')),
    decided_by      uuid,
    decision_reason text,
    same_reviewer   boolean     NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL,
    decided_at      timestamptz,
    version         bigint      NOT NULL DEFAULT 1
);
CREATE INDEX appeals_open ON trust.appeals (created_at) WHERE state = 'open';
CREATE INDEX appeals_by_user ON trust.appeals (user_id);
CREATE TRIGGER appeals_version BEFORE UPDATE ON trust.appeals
    FOR EACH ROW EXECUTE FUNCTION identity.bump_version();
GRANT SELECT, INSERT, UPDATE ON trust.appeals TO idleo_application;

INSERT INTO queues.sla_targets (sla, resolve_minutes, pauses, updated_at) VALUES ('appeal', 10080, false, now());

INSERT INTO identity.admin_role_permissions (role_id, permission)
SELECT role_id, permission FROM (VALUES
    ('owner', 'enforcement.read'), ('operations', 'enforcement.read'), ('support', 'enforcement.read'),
    ('trust_safety', 'enforcement.read'), ('auditor', 'enforcement.read'),
    ('owner', 'enforcement.apply'), ('trust_safety', 'enforcement.apply'),
    ('owner', 'enforcement.lift'), ('trust_safety', 'enforcement.lift'),
    ('owner', 'appeals.decide'), ('trust_safety', 'appeals.decide')
) AS granted (role_id, permission);
