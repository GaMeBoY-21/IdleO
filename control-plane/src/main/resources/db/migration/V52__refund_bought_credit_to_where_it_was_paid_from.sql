-- Refunds of bought credit (ADR 0039): only ever to the payment it was bought with, through Razorpay's Refunds API.
-- A renter asks (a money ticket opens), an admin approves an amount, and the credit leaves the renter's lots for
-- refunds_pending at once; each payment's part is refunded on its own, newest payment first, and posted out when
-- Razorpay says it's processed, or comes back to its lot if it fails. Deleting an account refunds what's left the same
-- way, and waits for it.

CREATE TABLE billing.credit_refunds (
    id             uuid        PRIMARY KEY,
    user_id        uuid        NOT NULL,
    cause          text        NOT NULL CHECK (cause IN ('request', 'deletion')),
    -- What the renter asked back (null: all they can) and what an admin approved.
    requested_paise bigint     CHECK (requested_paise IS NULL OR requested_paise > 0),
    approved_paise bigint      CHECK (approved_paise IS NULL OR approved_paise > 0),
    note           text        CHECK (note IS NULL OR length(note) <= 2000),
    ticket_id      uuid,
    ticket_number  bigint,
    -- requested: waiting for an admin; declined; refunding: parts sent; refunded: every part processed; failed: a part
    -- failed and its credit is back.
    status         text        NOT NULL CHECK (status IN ('requested', 'declined', 'refunding', 'refunded', 'failed')),
    decided_by     text,
    decision       text        CHECK (decision IS NULL OR length(decision) <= 500),
    requested_at   timestamptz NOT NULL,
    decided_at     timestamptz,
    finished_at    timestamptz
);
CREATE INDEX credit_refunds_by_user ON billing.credit_refunds (user_id, requested_at DESC);
CREATE INDEX credit_refunds_open ON billing.credit_refunds (requested_at) WHERE status IN ('requested', 'refunding');

-- One payment's part of a refund. receipt is ours (Razorpay's idempotency key for a refund, so sending it again can't
-- refund twice).
CREATE TABLE billing.refund_parts (
    id                 uuid        PRIMARY KEY,
    refund_id          uuid        NOT NULL REFERENCES billing.credit_refunds (id),
    topup_id           uuid        NOT NULL REFERENCES billing.topups (id),
    payment_id         text        NOT NULL,
    lot_id             uuid        NOT NULL,
    amount_paise       bigint      NOT NULL CHECK (amount_paise > 0),
    receipt            text        NOT NULL UNIQUE CHECK (length(receipt) <= 40),
    razorpay_refund_id text        UNIQUE,
    -- sending: not yet accepted by Razorpay (sent again until it is); pending: Razorpay is refunding it; processed; failed.
    status             text        NOT NULL CHECK (status IN ('sending', 'pending', 'processed', 'failed')),
    failure_reason     text,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL
);
CREATE INDEX refund_parts_open ON billing.refund_parts (created_at) WHERE status IN ('sending', 'pending');
CREATE INDEX refund_parts_by_payment ON billing.refund_parts (payment_id);
