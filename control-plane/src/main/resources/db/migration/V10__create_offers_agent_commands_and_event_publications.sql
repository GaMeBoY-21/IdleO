-- Booking (ADR 0015): durable events, the agents' command queue, and offers.

-- Spring Modulith's event publication registry: a publication is recorded in
-- the same transaction as the change that caused it, and retried until its
-- listener completes. Flyway owns the table; Modulith's own schema creation
-- stays off. The columns are Modulith 2.1's v2 PostgreSQL schema.
CREATE SCHEMA modulith;

CREATE TABLE modulith.event_publication (
    id                     uuid        NOT NULL PRIMARY KEY,
    listener_id            text        NOT NULL,
    event_type             text        NOT NULL,
    serialized_event       text        NOT NULL,
    publication_date       timestamptz NOT NULL,
    completion_date        timestamptz,
    status                 text,
    completion_attempts    integer,
    last_resubmission_date timestamptz
);

CREATE INDEX event_publication_serialized_event_hash_idx ON modulith.event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON modulith.event_publication (completion_date);

-- Everything the control plane sends an agent, kept until delivered (and, for
-- commands that need one, acknowledged). Valkey only routes; this table is the
-- truth (technical design lines 34, 177-189). message_id is reused on every
-- resend, so the agent can drop duplicates.
CREATE TABLE agentgateway.agent_commands (
    id           uuid        PRIMARY KEY,
    host_id      uuid        NOT NULL,
    session_id   uuid,
    type         text        NOT NULL,
    payload      bytea       NOT NULL,
    message_id   uuid        NOT NULL UNIQUE,
    requires_ack boolean     NOT NULL,
    status       text        NOT NULL CHECK (status IN ('pending', 'sent', 'acked', 'expired', 'withdrawn')),
    attempts     integer     NOT NULL DEFAULT 0,
    expires_at   timestamptz,
    created_at   timestamptz NOT NULL,
    sent_at      timestamptz,
    acked_at     timestamptz
);

CREATE INDEX agent_commands_undelivered ON agentgateway.agent_commands (host_id, created_at)
    WHERE status IN ('pending', 'sent');

-- Offers: up to three per session, each open for 10 seconds (technical design
-- lines 211, 228).
CREATE TABLE scheduling.offers (
    id             uuid        PRIMARY KEY,
    session_id     uuid        NOT NULL,
    host_id        uuid        NOT NULL,
    attempt_number integer     NOT NULL CHECK (attempt_number BETWEEN 1 AND 3),
    status         text        NOT NULL CHECK (status IN ('pending', 'accepted', 'declined', 'expired', 'withdrawn')),
    decline_reason text,
    expires_at     timestamptz NOT NULL,
    created_at     timestamptz NOT NULL,
    answered_at    timestamptz,
    UNIQUE (session_id, attempt_number)
);

CREATE INDEX offers_pending_by_expiry ON scheduling.offers (expires_at) WHERE status = 'pending';
CREATE INDEX offers_by_session ON scheduling.offers (session_id);
