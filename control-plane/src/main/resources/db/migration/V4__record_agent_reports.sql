-- What a host's agent reports when it connects (ADR 0011): its OS, agent
-- version and GPUs, and when it was last seen (written at most once a minute).
ALTER TABLE hosts.hosts
    ADD COLUMN os            text CHECK (os IN ('windows', 'linux')),
    ADD COLUMN agent_version text,
    ADD COLUMN last_seen_at  timestamptz;

-- One row per GPU the agent has reported, keyed by the GPU's own uuid. A GPU
-- missing from a later Hello is marked gone, never deleted.
CREATE TABLE hosts.host_gpus (
    host_id             uuid        NOT NULL REFERENCES hosts.hosts (id),
    gpu_uuid            text        NOT NULL,
    model               text        NOT NULL,
    vram_mb             integer     NOT NULL CHECK (vram_mb > 0),
    driver_version      text,
    cuda_driver_version text,
    compute_capability  text,
    first_seen_at       timestamptz NOT NULL,
    last_reported_at    timestamptz NOT NULL,
    gone_at             timestamptz,
    PRIMARY KEY (host_id, gpu_uuid)
);
