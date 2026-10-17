-- Linking a host by approval (ADR 0032): the agent asks to be linked, its owner approves in the web app, and the
-- agent's poll then receives a one-time enrolment ticket, exactly once. The poll token is stored only as a hash, and
-- the machine's own words (name, OS, agent version, GPUs) are what the approval page shows, never trusted for anything
-- else. A link is pending until approved or denied, and spent once its ticket has been handed out.
CREATE TABLE hosts.host_links (
    id              uuid        PRIMARY KEY,
    poll_token_hash bytea       NOT NULL,
    machine_name    text        NOT NULL,
    os              text        NOT NULL,
    agent_version   text        NOT NULL,
    gpus            jsonb       NOT NULL,
    created_at      timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL,
    approved_by     uuid,
    approved_at     timestamptz,
    host_id         uuid        REFERENCES hosts.hosts (id),
    denied_by       uuid,
    denied_at       timestamptz,
    redeemed_at     timestamptz,
    CHECK ((approved_by IS NULL) = (approved_at IS NULL)),
    CHECK ((approved_by IS NULL) = (host_id IS NULL)),
    CHECK ((denied_by IS NULL) = (denied_at IS NULL)),
    CHECK (approved_at IS NULL OR denied_at IS NULL),
    CHECK (redeemed_at IS NULL OR approved_at IS NOT NULL)
);
