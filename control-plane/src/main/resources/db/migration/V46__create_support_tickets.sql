-- Messages to support (ADR 0037): from the Contact page, signed in or not, and from Account, Help. A ticket's number
-- is what the person and the admin call it ("#1042"); its due date is set for a privacy request or a grievance
-- (legal.support.response-due). reply_to is where answers are emailed. Resolved tickets are deleted after
-- legal.retention.support-tickets; erasing an account empties the address and the person's own words.
CREATE SCHEMA IF NOT EXISTS support;

CREATE SEQUENCE support.ticket_numbers START WITH 1001;

CREATE TABLE support.tickets (
    id          uuid        PRIMARY KEY,
    number      bigint      NOT NULL UNIQUE DEFAULT nextval('support.ticket_numbers'),
    user_id     uuid,
    topic       text        NOT NULL CHECK (topic IN (
                                'session', 'money', 'hosting', 'account', 'privacy', 'report', 'grievance')),
    status      text        NOT NULL CHECK (status IN ('open', 'waiting', 'resolved')),
    reply_to    text,
    session_id  uuid,
    host_id     uuid,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    due_at      timestamptz,
    resolved_at timestamptz,
    CHECK ((status = 'resolved') = (resolved_at IS NOT NULL))
);

CREATE INDEX tickets_by_user ON support.tickets (user_id, created_at DESC) WHERE user_id IS NOT NULL;
CREATE INDEX tickets_open_by_due ON support.tickets (due_at) WHERE status <> 'resolved';

-- Each message of a ticket: the person's, or an admin's answer (by the admin's id).
CREATE TABLE support.messages (
    id         uuid        PRIMARY KEY,
    ticket_id  uuid        NOT NULL REFERENCES support.tickets (id) ON DELETE CASCADE,
    author     text        NOT NULL CHECK (author IN ('person', 'admin')),
    admin_id   uuid,
    body       text        NOT NULL CHECK (char_length(body) <= 5000),
    created_at timestamptz NOT NULL,
    CHECK ((author = 'admin') = (admin_id IS NOT NULL))
);

CREATE INDEX messages_by_ticket ON support.messages (ticket_id, created_at);
