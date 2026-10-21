-- Addresses the email provider said not to send to (docs/prompts/ops-foundation.md, docs/runbooks/email.md): a hard
-- bounce stops all email to the address until the person's address changes; a spam complaint stops the email they can
-- switch off. Kept as the address's SHA-256 (lowercased), never the address: the notifier and GET /v1/me ask about an
-- address they already have. One row per address; a bounce outranks a complaint. event_id is the provider's, so the
-- same event delivered again changes nothing.
CREATE TABLE notifications.email_suppressions (
    address_hash bytea       PRIMARY KEY CHECK (length(address_hash) = 32),
    reason       text        NOT NULL CHECK (reason IN ('bounced', 'complained')),
    event_id     text        NOT NULL,
    created_at   timestamptz NOT NULL
);
