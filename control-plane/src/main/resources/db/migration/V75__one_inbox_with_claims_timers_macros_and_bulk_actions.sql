-- The console's one inbox (ADR 0053). Each module says what's open (WorkQueue); this keeps who's on it and its timers.
-- Its version moves only when an admin changes an item: a timer moving on must not make someone's open page stale, so it
-- has no trigger.
CREATE SCHEMA IF NOT EXISTS queues;
GRANT USAGE ON SCHEMA queues TO idleo_application;

CREATE TABLE queues.sla_targets (
    sla            text        PRIMARY KEY,
    first_minutes  integer     CHECK (first_minutes > 0),
    resolve_minutes integer    CHECK (resolve_minutes > 0),
    pauses         boolean     NOT NULL,
    legal          boolean     NOT NULL DEFAULT false,
    updated_by     uuid,
    updated_at     timestamptz NOT NULL,
    version        bigint      NOT NULL DEFAULT 1
);
CREATE TRIGGER sla_targets_version BEFORE UPDATE ON queues.sla_targets
    FOR EACH ROW EXECUTE FUNCTION identity.bump_version();
-- LEGAL(confirm): grievances under the IT Rules 2021, and data requests against the DPDP Rules.
INSERT INTO queues.sla_targets (sla, first_minutes, resolve_minutes, pauses, legal, updated_at) VALUES
    ('grievance',     1440,  21600, false, true,  now()),
    ('ticket',        1440,  NULL,  true,  false, now()),
    ('chargeback',    NULL,  NULL,  false, false, now()),
    ('abuse-running', NULL,  15,    false, false, now()),
    ('abuse',         NULL,  1440,  true,  false, now()),
    ('report',        NULL,  1440,  true,  false, now()),
    ('data-request',  NULL,  43200, false, true,  now()),
    ('verification',  NULL,  2880,  true,  false, now()),
    ('review-minute', NULL,  1440,  false, false, now()),
    ('approval',      NULL,  NULL,  false, false, now());
GRANT SELECT, INSERT, UPDATE ON queues.sla_targets TO idleo_application;

CREATE TABLE queues.items (
    id              uuid        PRIMARY KEY,
    type            text        NOT NULL,
    source_id       text        NOT NULL,
    subject_kind    text,
    subject_id      uuid,
    person_id       uuid,
    title           text        NOT NULL,
    link            text        NOT NULL,
    sla             text        NOT NULL,
    opened_at       timestamptz NOT NULL,
    state           text        NOT NULL CHECK (state IN ('open', 'claimed', 'waiting', 'resolved')),
    assignee_id     uuid,
    claimed_at      timestamptz,
    last_action_at  timestamptz,
    first_reply_at  timestamptz,
    paused_since    timestamptz,
    paused_ms       bigint      NOT NULL DEFAULT 0,
    due_at          timestamptz,
    soon_at         timestamptz,
    breached_at     timestamptz,
    money_paise     bigint      NOT NULL DEFAULT 0,
    running_session boolean     NOT NULL DEFAULT false,
    score           integer     NOT NULL DEFAULT 0,
    override_score  integer,
    override_reason text,
    sort_key        bigint      NOT NULL DEFAULT 0,
    resolved_at     timestamptz,
    created_at      timestamptz NOT NULL,
    version         bigint      NOT NULL DEFAULT 1,
    UNIQUE (type, source_id)
);
CREATE INDEX items_by_priority ON queues.items (sort_key DESC, id DESC) WHERE state <> 'resolved';
CREATE INDEX items_mine ON queues.items (assignee_id, sort_key DESC, id DESC) WHERE state <> 'resolved';
CREATE INDEX items_unassigned ON queues.items (sort_key DESC, id DESC) WHERE state <> 'resolved' AND assignee_id IS NULL;
CREATE INDEX items_breaching ON queues.items (soon_at) WHERE state <> 'resolved' AND soon_at IS NOT NULL;
CREATE INDEX items_by_type ON queues.items (type, sort_key DESC, id DESC) WHERE state <> 'resolved';
CREATE INDEX items_idle ON queues.items (last_action_at) WHERE state = 'claimed';
CREATE INDEX items_by_person ON queues.items (person_id) WHERE person_id IS NOT NULL;
GRANT SELECT, INSERT, UPDATE ON queues.items TO idleo_application;

-- A macro's words and changes, a new version on each edit: an applied one is audited with the version it was.
CREATE TABLE queues.macros (
    id         uuid        PRIMARY KEY,
    type       text        NOT NULL,
    name       text        NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
    current    integer     NOT NULL,
    retired_at timestamptz,
    created_at timestamptz NOT NULL,
    version    bigint      NOT NULL DEFAULT 1
);
CREATE TRIGGER macros_version BEFORE UPDATE ON queues.macros
    FOR EACH ROW EXECUTE FUNCTION identity.bump_version();
CREATE TABLE queues.macro_versions (
    macro_id     uuid        NOT NULL REFERENCES queues.macros (id),
    number       integer     NOT NULL,
    body         text        CHECK (length(body) <= 5000),
    set_status   text        CHECK (set_status IN ('open', 'waiting', 'resolved')),
    add_tag      text,
    set_priority text        CHECK (set_priority IN ('urgent', 'high', 'normal', 'low')),
    written_by   uuid        NOT NULL,
    written_at   timestamptz NOT NULL,
    PRIMARY KEY (macro_id, number)
);
GRANT SELECT, INSERT, UPDATE ON queues.macros TO idleo_application;
GRANT SELECT, INSERT ON queues.macro_versions TO idleo_application;

-- A bulk action: one row, its items' before and after, so it can report item by item and be undone.
CREATE TABLE queues.bulk_batches (
    id           uuid        PRIMARY KEY,
    requested_by uuid        NOT NULL,
    action       jsonb       NOT NULL,
    reason       text        NOT NULL,
    state        text        NOT NULL CHECK (state IN ('queued', 'running', 'done')),
    total        integer     NOT NULL,
    undoable     boolean     NOT NULL,
    heartbeat_at timestamptz,
    finished_at  timestamptz,
    undo_until   timestamptz,
    undone_at    timestamptz,
    created_at   timestamptz NOT NULL
);
CREATE INDEX bulk_batches_to_run ON queues.bulk_batches (created_at) WHERE state <> 'done';
CREATE TABLE queues.bulk_items (
    batch_id uuid    NOT NULL REFERENCES queues.bulk_batches (id),
    item_id  uuid    NOT NULL REFERENCES queues.items (id),
    position integer NOT NULL,
    before   jsonb,
    after    jsonb,
    state    text    NOT NULL CHECK (state IN ('pending', 'done', 'skipped', 'failed', 'undone')),
    failure  text,
    PRIMARY KEY (batch_id, item_id)
);
GRANT SELECT, INSERT, UPDATE ON queues.bulk_batches TO idleo_application;
GRANT SELECT, INSERT, UPDATE ON queues.bulk_items TO idleo_application;

INSERT INTO identity.admin_role_permissions (role_id, permission)
SELECT role_id, permission FROM (VALUES
    ('owner', 'queues.read'), ('operations', 'queues.read'), ('support', 'queues.read'),
    ('trust_safety', 'queues.read'), ('finance', 'queues.read'), ('auditor', 'queues.read'),
    ('owner', 'queues.claim'), ('operations', 'queues.claim'), ('support', 'queues.claim'),
    ('trust_safety', 'queues.claim'), ('finance', 'queues.claim'),
    ('owner', 'queues.assign'), ('operations', 'queues.assign'),
    ('owner', 'queues.sla'),
    ('owner', 'queues.bulk'), ('operations', 'queues.bulk'), ('support', 'queues.bulk'), ('trust_safety', 'queues.bulk'),
    ('owner', 'macros.manage'), ('support', 'macros.manage')
) AS granted (role_id, permission);
