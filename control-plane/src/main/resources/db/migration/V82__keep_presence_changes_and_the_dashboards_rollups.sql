-- Each host's presence over time (ADR 0056): only its changes, never an address. A new stream is an 'online' row of its
-- own, so a reconnect counts even when the new stream replaced the old before it closed. Kept 90 days
-- (retention.periods.presence-changes).
CREATE TABLE agentgateway.presence_changes (
    id uuid PRIMARY KEY,
    host_id uuid NOT NULL,
    state text NOT NULL CHECK (state IN ('online', 'offline', 'unknown')),
    at timestamptz NOT NULL
);
CREATE INDEX presence_changes_by_host ON agentgateway.presence_changes (host_id, at);
CREATE INDEX presence_changes_by_time ON agentgateway.presence_changes (at);
GRANT SELECT, INSERT, DELETE ON agentgateway.presence_changes TO idleo_application;

-- Each host's state as last recorded and since when: what a change is checked against, so a late observation never
-- writes over a newer one.
CREATE TABLE agentgateway.presence_states (
    host_id uuid PRIMARY KEY,
    state text NOT NULL CHECK (state IN ('online', 'offline', 'unknown')),
    since timestamptz NOT NULL
);
GRANT SELECT, INSERT, UPDATE, DELETE ON agentgateway.presence_states TO idleo_application;

-- The live dashboard's rollups (ADR 0056): each part as its module last gave it, worked out by one job, read by every
-- page view.
CREATE TABLE console.rollups (
    part text PRIMARY KEY,
    body jsonb NOT NULL,
    took_ms integer NOT NULL,
    computed_at timestamptz NOT NULL
);
GRANT SELECT, INSERT, UPDATE ON console.rollups TO idleo_application;

-- The live dashboard takes the place of the first one: every role that could read that reads this (money's figures
-- only with money.read too). The old permission's rows stay while the release before this one serves.
INSERT INTO identity.admin_role_permissions (role_id, permission)
SELECT role_id, 'ops.dashboard.read' FROM identity.admin_role_permissions WHERE permission = 'dashboard.read'
ON CONFLICT DO NOTHING;
