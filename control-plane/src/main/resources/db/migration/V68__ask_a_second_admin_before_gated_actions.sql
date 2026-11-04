-- Maker-checker (ADR 0051). An action of a gated kind becomes a request: the exact action, its parameters, the target's
-- version, who asked and why. Another admin with the action's permission approves or rejects it. With no one else to ask,
-- it waits out a cooling-off delay instead, and the Owner is told.
CREATE TABLE identity.approval_requests (
    id                uuid        PRIMARY KEY,
    kind              text        NOT NULL CHECK (kind <> ''),
    permission        text        NOT NULL CHECK (permission <> ''),
    target_type       text,
    target_id         text,
    target_version    bigint,
    params            jsonb       NOT NULL,
    changes           jsonb       NOT NULL,
    requested_by      uuid        NOT NULL,
    requested_session uuid,
    reason            text        NOT NULL CHECK (reason <> ''),
    state             text        NOT NULL CHECK (state IN ('pending', 'running', 'done', 'rejected', 'expired',
                                                            'cancelled', 'failed')),
    solo              boolean     NOT NULL,
    run_after         timestamptz,
    decided_by        uuid,
    decision_reason   text,
    decided_at        timestamptz,
    failure           text,
    download_kind     text,
    downloaded_at     timestamptz,
    expires_at        timestamptz NOT NULL,
    created_at        timestamptz NOT NULL,
    finished_at       timestamptz,
    version           bigint      NOT NULL DEFAULT 1,
    CHECK ((target_type IS NULL) = (target_id IS NULL))
);
CREATE INDEX approval_requests_pending ON identity.approval_requests (expires_at) WHERE state = 'pending';
CREATE INDEX approval_requests_by_requester ON identity.approval_requests (requested_by, id DESC);
GRANT SELECT, INSERT, UPDATE ON identity.approval_requests TO idleo_application;

CREATE TRIGGER approval_requests_version BEFORE UPDATE ON identity.approval_requests
    FOR EACH ROW WHEN (OLD.* IS DISTINCT FROM NEW.*)
    EXECUTE FUNCTION identity.bump_version();

-- An admin's version: it moves when their roles do.
ALTER TABLE identity.admins ADD COLUMN version bigint NOT NULL DEFAULT 1;

-- Everyone can see the queue; everyone but the Auditor, who only reads, can decide what their other permissions allow.
INSERT INTO identity.admin_role_permissions (role_id, permission)
SELECT role_id, 'approvals.read' FROM unnest(ARRAY['owner', 'operations', 'support', 'trust_safety', 'finance', 'auditor']) AS role_id;
INSERT INTO identity.admin_role_permissions (role_id, permission)
SELECT role_id, 'approvals.decide' FROM unnest(ARRAY['owner', 'operations', 'support', 'trust_safety', 'finance']) AS role_id;
