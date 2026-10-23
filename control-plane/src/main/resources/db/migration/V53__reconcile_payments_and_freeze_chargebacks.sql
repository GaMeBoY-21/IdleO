-- Razorpay against our ledger (ADR 0039). Once a day, and when an admin asks, a day's payments and refunds are listed
-- from Razorpay's API and compared one by one with our top-ups and refund parts; each disagreement is an item an admin
-- sees, kept once by what it is and which of Razorpay's ids it's about, so running the same day again adds nothing.
-- And chargebacks: a dispute on a payment freezes the renter's bought credit, up to the disputed amount, until Razorpay
-- says how it ended.

CREATE TABLE billing.payment_reconciliation_runs (
    id          uuid        PRIMARY KEY,
    -- The day in India whose payments and refunds were compared.
    day         date        NOT NULL,
    -- nightly, or who asked (an admin's or operator's label).
    trigger     text        NOT NULL,
    started_at  timestamptz NOT NULL,
    finished_at timestamptz,
    payments    integer     CHECK (payments IS NULL OR payments >= 0),
    refunds     integer     CHECK (refunds IS NULL OR refunds >= 0),
    -- What this run found wrong, and how many items were open once it finished.
    found       integer     CHECK (found IS NULL OR found >= 0),
    open        integer     CHECK (open IS NULL OR open >= 0),
    -- Razorpay couldn't be reached, or answered something that isn't its API's.
    failure     text
);
-- One nightly run a day, whichever instance claims it first.
CREATE UNIQUE INDEX payment_reconciliation_runs_one_nightly ON billing.payment_reconciliation_runs (day)
    WHERE trigger = 'nightly';
CREATE INDEX payment_reconciliation_runs_newest ON billing.payment_reconciliation_runs (started_at DESC);

CREATE TABLE billing.payment_mismatches (
    id            uuid        PRIMARY KEY,
    kind          text        NOT NULL CHECK (kind IN ('missing_ours', 'missing_theirs', 'amount', 'status',
                                                       'captured_not_credited', 'authorized_not_captured')),
    -- Razorpay's payment or refund id the item is about.
    subject       text        NOT NULL,
    payment_id    text,
    topup_id      uuid        REFERENCES billing.topups (id),
    user_id       uuid,
    our_paise     bigint,
    their_paise   bigint,
    our_status    text,
    their_status  text,
    detail        text        NOT NULL CHECK (length(detail) <= 500),
    -- open; fixed: a later run found it right; accepted: an admin looked and closed it, with a reason.
    state         text        NOT NULL CHECK (state IN ('open', 'fixed', 'accepted')),
    first_run_id  uuid        NOT NULL REFERENCES billing.payment_reconciliation_runs (id),
    last_run_id   uuid        NOT NULL REFERENCES billing.payment_reconciliation_runs (id),
    found_at      timestamptz NOT NULL,
    last_found_at timestamptz NOT NULL,
    closed_at     timestamptz,
    closed_by     text,
    close_reason  text        CHECK (close_reason IS NULL OR length(close_reason) <= 500),
    UNIQUE (kind, subject)
);
CREATE INDEX payment_mismatches_open ON billing.payment_mismatches (found_at) WHERE state = 'open';
CREATE INDEX payment_mismatches_by_user ON billing.payment_mismatches (user_id);

-- A dispute Razorpay told us about (https://razorpay.com/docs/webhooks/disputes/), by its id. status is Razorpay's
-- (open, under_review, won, lost, closed); outcome is ours once it's final: what was lost (amount_deducted, or the
-- disputed amount for a lost one that says nothing was deducted) leaves the frozen credit and, beyond it, is a loss;
-- the rest of the freeze goes back to the lots it came from.
CREATE TABLE billing.chargebacks (
    id             uuid        PRIMARY KEY,
    dispute_id     text        NOT NULL UNIQUE,
    payment_id     text        NOT NULL,
    -- Null when the payment isn't one of our top-ups: nothing is frozen, and the reconciliation lists the payment.
    topup_id       uuid        REFERENCES billing.topups (id),
    user_id        uuid,
    amount_paise   bigint      NOT NULL CHECK (amount_paise > 0),
    currency       text        NOT NULL,
    reason_code    text,
    phase          text,
    status         text        NOT NULL,
    respond_by     timestamptz,
    deducted_paise bigint      NOT NULL CHECK (deducted_paise >= 0),
    -- What's frozen in disputes_frozen now, and once it's final, what was lost and what went back.
    frozen_paise   bigint      NOT NULL CHECK (frozen_paise >= 0),
    lost_paise     bigint      CHECK (lost_paise IS NULL OR lost_paise >= 0),
    released_paise bigint      CHECK (released_paise IS NULL OR released_paise >= 0),
    outcome        text        CHECK (outcome IN ('won', 'lost')),
    -- Razorpay's created_at for the dispute, and when we first heard of it.
    opened_at      timestamptz NOT NULL,
    received_at    timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    settled_at     timestamptz,
    CONSTRAINT chargebacks_settled_has_outcome CHECK ((outcome IS NULL) = (settled_at IS NULL))
);
CREATE INDEX chargebacks_open ON billing.chargebacks (respond_by) WHERE outcome IS NULL;
CREATE INDEX chargebacks_by_user ON billing.chargebacks (user_id);
CREATE INDEX chargebacks_by_payment ON billing.chargebacks (payment_id);

-- Money records: kept, never deleted.
REVOKE DELETE, TRUNCATE ON billing.payment_mismatches, billing.chargebacks FROM PUBLIC;
REVOKE DELETE, TRUNCATE ON billing.payment_mismatches, billing.chargebacks FROM CURRENT_USER;
