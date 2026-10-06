-- Starting sessions (ADR 0016).

-- One row per attempt of a session, written when its host accepts the offer:
-- the host and GPUs it runs on, the StartSession's command_id (the agent's
-- idempotency key, the same on every redelivery), the session-wide minute
-- index of its first minute, and the checkpoint it resumes from, if any.
-- start_delivered_at is the first time the StartSession went down the host's
-- stream; from then on the agent may hold a lease.
CREATE TABLE sessions.attempts (
    session_id          uuid        NOT NULL REFERENCES sessions.sessions (id),
    attempt             integer     NOT NULL CHECK (attempt >= 1),
    host_id             uuid        NOT NULL,
    gpu_uuids           text[]      NOT NULL CHECK (cardinality(gpu_uuids) >= 1),
    command_id          uuid        NOT NULL UNIQUE,
    minute_index_base   integer     NOT NULL CHECK (minute_index_base >= 0),
    resume_from_attempt integer,
    resume_from_seq     integer,
    start_delivered_at  timestamptz,
    created_at          timestamptz NOT NULL,
    PRIMARY KEY (session_id, attempt),
    CHECK ((resume_from_attempt IS NULL) = (resume_from_seq IS NULL)),
    CHECK (resume_from_attempt IS NULL OR (resume_from_attempt < attempt AND resume_from_seq >= 1))
);

-- Offers belong to one attempt of a session: a re-matched session gets up to
-- three offers again, and may go back to the host that lost it. Each offer
-- names the GPU it would run on. Offers made before this migration are all
-- answered or expired; they keep attempt 1 and an empty GPU.
ALTER TABLE scheduling.offers ADD COLUMN session_attempt integer NOT NULL DEFAULT 1 CHECK (session_attempt >= 1);
ALTER TABLE scheduling.offers ALTER COLUMN session_attempt DROP DEFAULT;
ALTER TABLE scheduling.offers ADD COLUMN gpu_uuid text NOT NULL DEFAULT '';
ALTER TABLE scheduling.offers ALTER COLUMN gpu_uuid DROP DEFAULT;
ALTER TABLE scheduling.offers DROP CONSTRAINT offers_session_id_attempt_number_key;
ALTER TABLE scheduling.offers ADD CONSTRAINT offers_one_number_per_attempt
    UNIQUE (session_id, session_attempt, attempt_number);
