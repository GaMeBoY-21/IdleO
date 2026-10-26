-- Renters' own container images (ADR 0042), checked on our server before one can reach a host. The images module's own
-- schema; sessions keeps which image a session runs (the reference it runs, pinned, is sessions.sessions.image_digest).
CREATE SCHEMA IF NOT EXISTS images;

-- A private registry's read-only token, as the renter gave it: sealed under images.registry-token.key (AES-256-GCM, the
-- row's id and the renter's as associated data), opened only to pull (by the scanner, and for a session's StartSession,
-- never stored there), deleted legal.retention.registry-tokens after it was last used.
CREATE TABLE images.registry_logins (
    id           uuid        PRIMARY KEY,
    user_id      uuid        NOT NULL,
    registry     text        NOT NULL,
    username     text        NOT NULL,
    sealed_token bytea       NOT NULL,
    created_at   timestamptz NOT NULL,
    last_used_at timestamptz NOT NULL
);
CREATE INDEX registry_logins_by_user ON images.registry_logins (user_id);
CREATE INDEX registry_logins_by_use ON images.registry_logins (last_used_at);

-- One reference a renter submitted, and its check: queued, then checking (claimed by a scanner, its claim kept alive by
-- the scanner's progress), then ready, blocked or failed (not checked: a crash, a time-out, a scanner that never came
-- back; never a pass). The digest is the one platform manifest the reference resolved to when it was checked: the only
-- thing a session with it ever runs. A new push to the tag is a new submission.
CREATE TABLE images.images (
    id               uuid        PRIMARY KEY,
    user_id          uuid        NOT NULL,
    reference        text        NOT NULL,
    registry         text        NOT NULL,
    repository       text        NOT NULL,
    login_id         uuid        REFERENCES images.registry_logins (id) ON DELETE SET NULL,
    -- Whether it was submitted with a token: a private image needs one to be pulled.
    private          boolean     NOT NULL,
    digest           text        CHECK (digest ~ '^sha256:[0-9a-f]{64}$'),
    state            text        NOT NULL CHECK (state IN ('queued', 'checking', 'ready', 'blocked', 'failed')),
    -- Why it's blocked or failed, as a code (ImageVerdicts), and the renter's words.
    reason           text,
    detail           text,
    attempts         integer     NOT NULL DEFAULT 0,
    claimed_by       text,
    claim_expires_at timestamptz,
    -- The check's progress, for the screen: downloading, reading, scanning, or waiting for the blocklist.
    phase            text,
    done_bytes       bigint      NOT NULL DEFAULT 0,
    total_bytes      bigint      NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    checked_at       timestamptz,
    -- When a session last booked it; images are deleted retention.periods.images after their last use or check.
    last_used_at     timestamptz,
    CONSTRAINT images_verdict_has_reason CHECK ((state IN ('blocked', 'failed')) = (reason IS NOT NULL))
);
CREATE INDEX images_queue ON images.images (created_at) WHERE state IN ('queued', 'checking');
CREATE INDEX images_by_user ON images.images (user_id, created_at DESC);
CREATE INDEX images_by_digest ON images.images (digest);

-- What a check found for one digest, cached: a second renter's submission of the same image is answered from here,
-- once its tag has been resolved. Judged against the abuse blocklist of blocklist_version; a newer list judges it again
-- from the hashes kept below, without downloading it twice.
CREATE TABLE images.scans (
    digest            text        PRIMARY KEY CHECK (digest ~ '^sha256:[0-9a-f]{64}$'),
    compressed_bytes  bigint      NOT NULL,
    unpacked_bytes    bigint      NOT NULL,
    layers            integer     NOT NULL,
    -- NVIDIA_REQUIRE_CUDA and CUDA_VERSION as written, and the version they need ("12.4"), for matching.
    cuda_require      text,
    cuda_version      text,
    cuda_needed       text,
    entrypoint        jsonb       NOT NULL,
    cmd               jsonb       NOT NULL,
    working_dir       text,
    image_user        text,
    has_shell         boolean     NOT NULL,
    critical          integer     NOT NULL,
    high              integer     NOT NULL,
    medium            integer     NOT NULL,
    low               integer     NOT NULL,
    -- Trivy's list (at most 5,000, most severe first) and what looked like a secret ({path, rule, category, title}; never
    -- the secret itself).
    vulnerabilities   jsonb       NOT NULL,
    secrets           jsonb       NOT NULL,
    trivy_version     text        NOT NULL,
    checked_at        timestamptz NOT NULL,
    -- The blocklist it was last judged by; empty until one is cached, and until then nothing with it is ready.
    blocklist_version text        NOT NULL DEFAULT '',
    -- blocklisted or admin, the words, and for an admin's block who and when.
    blocked_reason    text        CHECK (blocked_reason IN ('blocklisted', 'admin')),
    blocked_detail    text,
    blocked_at        timestamptz,
    -- The listed program's path, for admins only.
    blocked_path      text
);
CREATE INDEX scans_to_judge ON images.scans (blocklist_version) WHERE blocked_reason IS NULL;

-- Every ELF file in every layer of a scanned image, by SHA-256, even one a later layer deleted.
CREATE TABLE images.scan_executables (
    digest text    NOT NULL REFERENCES images.scans (digest) ON DELETE CASCADE,
    sha256 text    NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    path   text    NOT NULL,
    layer  integer NOT NULL
);
CREATE INDEX scan_executables_by_hash ON images.scan_executables (sha256);
CREATE INDEX scan_executables_by_digest ON images.scan_executables (digest);

CREATE TABLE images.idempotency_keys (
    user_id           uuid        NOT NULL,
    idempotency_key   text        NOT NULL,
    request_hash      bytea       NOT NULL,
    state             text        NOT NULL CHECK (state IN ('in_progress', 'completed')),
    response_status   integer,
    response_location text,
    response_body     text,
    locked_at         timestamptz NOT NULL,
    created_at        timestamptz NOT NULL,
    expires_at        timestamptz NOT NULL,
    PRIMARY KEY (user_id, idempotency_key)
);

-- A session of a renter's own image names it; the reference it runs, pinned by digest, is sessions.sessions.image_digest.
ALTER TABLE sessions.sessions ADD COLUMN image_id uuid;

-- The image's download on the host, as its heartbeats report it: "Downloading your image · 3.1 of 8.4 GB".
ALTER TABLE sessions.attempts ADD COLUMN image_done_bytes bigint NOT NULL DEFAULT 0;
ALTER TABLE sessions.attempts ADD COLUMN image_total_bytes bigint NOT NULL DEFAULT 0;
