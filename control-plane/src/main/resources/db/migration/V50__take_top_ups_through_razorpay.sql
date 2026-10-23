-- Top-ups through Razorpay (ADR 0039). A renter's top-up is a Razorpay order; the credit is posted only once the
-- server has proof the payment was captured, for this order, this amount, in INR, whether the browser's handler or a
-- webhook brings it first. Only Razorpay's ids, the method and, for a card, its network and last four digits are kept:
-- no card data ever reaches us.

CREATE TABLE billing.topups (
    id                  uuid        PRIMARY KEY,
    user_id             uuid        NOT NULL,
    amount_paise        bigint      NOT NULL CHECK (amount_paise > 0),
    currency            text        NOT NULL CHECK (currency = 'INR'),
    -- Razorpay's receipt (ours, unique, at most 40 ASCII characters) and its order id.
    receipt             text        NOT NULL UNIQUE CHECK (length(receipt) <= 40),
    order_id            text        NOT NULL UNIQUE,
    -- pending: ordered, no captured payment yet; paid: credited; failed: the last attempt failed (another attempt on
    -- the same order can still be captured); abandoned: pending for a day.
    status              text        NOT NULL CHECK (status IN ('pending', 'paid', 'failed', 'abandoned')),
    payment_id          text        UNIQUE,
    method              text,
    card_network        text,
    card_last4          text        CHECK (card_last4 IS NULL OR card_last4 ~ '^[0-9]{4}$'),
    failure_reason      text,
    -- Who brought the proof: the browser's handler, a webhook, or the reconciliation.
    credited_by         text        CHECK (credited_by IN ('checkout', 'webhook', 'reconciliation')),
    txn_id              uuid        UNIQUE,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    paid_at             timestamptz,
    CONSTRAINT topups_paid_has_payment CHECK (status <> 'paid' OR (payment_id IS NOT NULL AND txn_id IS NOT NULL))
);
CREATE INDEX topups_by_user ON billing.topups (user_id, created_at DESC);
CREATE INDEX topups_pending ON billing.topups (created_at) WHERE status = 'pending';

-- Each Razorpay webhook event we've handled, by x-razorpay-event-id, so a repeat changes nothing. What it was about
-- by Razorpay's ids only: never its payload, which carries the payer's email and phone.
CREATE TABLE billing.payment_events (
    event_id    text        PRIMARY KEY,
    event       text        NOT NULL,
    payment_id  text,
    order_id    text,
    refund_id   text,
    dispute_id  text,
    received_at timestamptz NOT NULL
);

-- Failed payments, for the velocity rule: more than 5 in an hour block a renter's top-ups for an hour.
CREATE TABLE billing.topup_failures (
    payment_id text        PRIMARY KEY,
    user_id    uuid        NOT NULL,
    topup_id   uuid        NOT NULL REFERENCES billing.topups (id),
    failed_at  timestamptz NOT NULL
);
CREATE INDEX topup_failures_by_user ON billing.topup_failures (user_id, failed_at);

-- Idempotency-Key records for POST /v1/wallet/topups, scoped to the user.
CREATE TABLE billing.idempotency_keys (
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

-- The events are a record of what Razorpay told us: added to, never changed.
CREATE TRIGGER payment_events_append_only
    BEFORE UPDATE OR DELETE ON billing.payment_events
    FOR EACH ROW EXECUTE FUNCTION billing.refuse_change();
