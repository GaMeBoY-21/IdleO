-- Staged rollouts of the Windows app (ADR 0056): who is offered a signed release, decided here; the agent's own checks of
-- the signature don't change. percent: 5, 25, 50 or 100 of hosts, chosen by host id; never more than the signed
-- manifest's own rollout_percent. paused: no host is offered it, until it's widened again.
CREATE TABLE hosts.agent_rollouts (
    release text PRIMARY KEY,
    percent integer NOT NULL CHECK (percent IN (5, 25, 50, 100)),
    paused boolean NOT NULL DEFAULT false,
    reason text NOT NULL,
    changed_by uuid,
    changed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1
);
CREATE TRIGGER agent_rollouts_version BEFORE UPDATE ON hosts.agent_rollouts
    FOR EACH ROW EXECUTE FUNCTION identity.bump_version();
GRANT SELECT, INSERT, UPDATE ON hosts.agent_rollouts TO idleo_application;

-- Which signed abuse blocklist each host's agent last said it holds, for the fleet view.
ALTER TABLE hosts.hosts ADD COLUMN blocklist_version text;

-- Broadcast notices (ADR 0056): to renters, hosts, a tag or an agent-version range, at a time, in the app and by email
-- when asked; sent once, logged.
CREATE TABLE support.broadcasts (
    id uuid PRIMARY KEY,
    subject text NOT NULL,
    body text NOT NULL,
    audience text NOT NULL CHECK (audience IN ('renters', 'hosts', 'tag', 'agent_versions')),
    tag text,
    min_agent_version text,
    max_agent_version text,
    email boolean NOT NULL,
    send_at timestamptz NOT NULL,
    state text NOT NULL CHECK (state IN ('scheduled', 'sending', 'sent', 'cancelled')),
    reached integer,
    created_by uuid NOT NULL,
    reason text NOT NULL,
    created_at timestamptz NOT NULL,
    sent_at timestamptz,
    version bigint NOT NULL DEFAULT 1,
    CONSTRAINT broadcasts_tag_named CHECK ((audience = 'tag') = (tag IS NOT NULL)),
    CONSTRAINT broadcasts_range_named CHECK (
        audience <> 'agent_versions' OR (min_agent_version IS NOT NULL OR max_agent_version IS NOT NULL))
);
CREATE INDEX broadcasts_due ON support.broadcasts (send_at) WHERE state = 'scheduled';
CREATE TRIGGER broadcasts_version BEFORE UPDATE ON support.broadcasts
    FOR EACH ROW EXECUTE FUNCTION identity.bump_version();
GRANT SELECT, INSERT, UPDATE ON support.broadcasts TO idleo_application;

-- The fleet and rollouts: Operations and the Owner; widening is the Owner's and Operations', with a factor and another
-- admin's approval.
INSERT INTO identity.admin_role_permissions (role_id, permission) VALUES
    ('owner', 'fleet.read'), ('operations', 'fleet.read'), ('support', 'fleet.read'), ('auditor', 'fleet.read'),
    ('owner', 'rollouts.pause'), ('operations', 'rollouts.pause'),
    ('owner', 'rollouts.widen'), ('operations', 'rollouts.widen'),
    ('owner', 'broadcasts.send'), ('operations', 'broadcasts.send');
