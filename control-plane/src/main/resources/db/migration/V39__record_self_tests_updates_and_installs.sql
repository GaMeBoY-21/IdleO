-- The Windows app (docs/prompts/windows-app.md): what a host's agent reports about itself beyond Hello, and how the
-- app's installs went.

-- The host's latest self-test (its distro, the GPU inside it, the lockdown, the disk and the speed to storage), one row
-- per host, replaced by each report. With hosts.auto-activate on, a pending host whose self-test passed goes live; with
-- it off, the admin console shows it beside the Approve button.
CREATE TABLE hosts.self_tests (
    host_id        uuid        PRIMARY KEY REFERENCES hosts.hosts (id),
    passed         boolean     NOT NULL,
    distro_version text        NOT NULL,
    agent_version  text,
    checks         jsonb       NOT NULL,
    reported_at    timestamptz NOT NULL
);

-- How each update offered to a host went, the latest per version. The control plane never offers a version again to a
-- host that rolled it back.
CREATE TABLE hosts.update_results (
    host_id     uuid        NOT NULL REFERENCES hosts.hosts (id),
    version     text        NOT NULL,
    outcome     text        NOT NULL CHECK (outcome IN ('installed', 'rolled_back', 'failed_verify', 'failed_download')),
    detail      text        NOT NULL,
    reported_at timestamptz NOT NULL,
    PRIMARY KEY (host_id, version)
);

CREATE INDEX update_results_latest ON hosts.update_results (host_id, reported_at DESC);

-- The setup app's measurements (host experience §6 row 11): each step's start and end, sent without a sign-in under
-- an install id the app makes. Never the Windows user name, a path or the machine's serial. At most 200 per install.
CREATE TABLE hosts.install_events (
    id            uuid        PRIMARY KEY,
    install_id    uuid        NOT NULL,
    step          text        NOT NULL,
    outcome       text        NOT NULL CHECK (outcome IN ('started', 'passed', 'failed', 'fixed')),
    reason        text,
    duration_ms   bigint      CHECK (duration_ms >= 0),
    at            timestamptz NOT NULL,
    setup_version text,
    windows_build integer,
    gpu_model     text,
    received_at   timestamptz NOT NULL
);

CREATE INDEX install_events_by_install ON hosts.install_events (install_id, at);
CREATE INDEX install_events_by_time ON hosts.install_events (received_at);

-- The install a link came from, so an install is tied to its host once its link is approved.
ALTER TABLE hosts.host_links ADD COLUMN install_id uuid;

CREATE INDEX host_links_by_install ON hosts.host_links (install_id) WHERE install_id IS NOT NULL;
