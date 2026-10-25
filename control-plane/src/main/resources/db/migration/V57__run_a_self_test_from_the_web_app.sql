-- A self-test asked for in the web app, by a host's owner or an admin (ADR 0034): the agent runs its full self-test
-- over its stream and each check shows on the page as it arrives. One run at a time, none while a session runs, and at
-- most 6 a host in 24 hours; those limits read this table.
CREATE TABLE hosts.self_test_runs (
    id           uuid        PRIMARY KEY,
    host_id      uuid        NOT NULL REFERENCES hosts.hosts (id),
    -- 'owner' or 'admin', and which user pressed the button.
    requested_by_kind text   NOT NULL CHECK (requested_by_kind IN ('owner', 'admin')),
    requested_by uuid        NOT NULL,
    requested_at timestamptz NOT NULL,
    state        text        NOT NULL CHECK (state IN ('running', 'passed', 'failed', 'refused')),
    -- Every check that has arrived, in order: [{name, passed, detail, tookMs}].
    checks       jsonb       NOT NULL DEFAULT '[]',
    -- Why the agent wouldn't run it: session_running or already_running.
    refused      text,
    finished_at  timestamptz,
    CONSTRAINT self_test_runs_finished_together CHECK ((state = 'running') = (finished_at IS NULL)),
    CONSTRAINT self_test_runs_refusal_recorded CHECK ((state = 'refused') = (refused IS NOT NULL))
);
CREATE INDEX self_test_runs_by_host ON hosts.self_test_runs (host_id, requested_at DESC);
