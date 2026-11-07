-- Money operations (ADR 0055): what Razorpay settles to our bank, matched item by item against the ledger, and the
-- weekly payouts as one batch, previewed and then released by approval.

-- One more account, not owned: bank, an asset raised by debits, what Razorpay has settled into our bank account. A
-- settlement moves money out of gateway_clearing into it, less Razorpay's fees (gateway_fees) and their GST
-- (gst_input_credit).
-- contract: ledger_entries_account_check is replaced by a wider list; the release before writes only accounts both lists allow, safe since f1889692
ALTER TABLE billing.ledger_entries DROP CONSTRAINT ledger_entries_account_check;
ALTER TABLE billing.ledger_entries ADD CONSTRAINT ledger_entries_account_check CHECK (account IN (
    'renter_wallet', 'renter_purchased', 'host_payable', 'platform_revenue', 'gateway_clearing', 'promotions',
    'refunds_pending', 'disputes_frozen', 'tax_cgst', 'tax_sgst', 'tax_igst', 'gst_on_unused_credit',
    'free_credit_expired', 'chargeback_losses', 'payouts_in_flight', 'payouts_paid', 'tds_payable', 'host_receivable',
    'earnings_forfeited', 'gateway_fees', 'gst_input_credit', 'write_offs', 'bank')) NOT VALID;
ALTER TABLE billing.ledger_entries VALIDATE CONSTRAINT ledger_entries_account_check;

-- Each settlement Razorpay made (https://razorpay.com/docs/api/settlements/fetch-all/), by its own id, fetched again
-- each day until it's processed. Its fees and their tax are posted, with the money it moved to the bank, once.
CREATE TABLE billing.settlements (
    id           text        PRIMARY KEY CHECK (id <> ''),
    amount_paise bigint      NOT NULL CHECK (amount_paise >= 0),
    fees_paise   bigint      NOT NULL CHECK (fees_paise >= 0),
    tax_paise    bigint      NOT NULL CHECK (tax_paise >= 0),
    status       text        NOT NULL CHECK (status <> ''),
    utr          text,
    settled_at   timestamptz NOT NULL,
    fetched_at   timestamptz NOT NULL,
    -- matched: every item agrees with the ledger; partly: some do; unmatched: none does, or the sum isn't what was
    -- settled.
    state        text        NOT NULL CHECK (state IN ('matched', 'partly', 'unmatched')),
    txn_id       uuid        UNIQUE,
    version      bigint      NOT NULL CHECK (version >= 1)
);
CREATE INDEX settlements_newest ON billing.settlements (settled_at DESC, id);
GRANT SELECT, INSERT, UPDATE ON billing.settlements TO idleo_application;

-- What a settlement carried, from Razorpay's combined settlement reconciliation report
-- (https://razorpay.com/docs/api/settlements/fetch-recon/), and how each item matches ours. An item we have that no
-- settlement carried, a while after it was captured, is kept with no settlement (missing_theirs).
CREATE TABLE billing.settlement_items (
    id            uuid        PRIMARY KEY,
    settlement_id text        REFERENCES billing.settlements (id),
    -- Razorpay's payment, refund or adjustment id, or ours for missing_theirs: the payment's.
    entity_id     text        NOT NULL CHECK (entity_id <> ''),
    type          text        NOT NULL CHECK (type IN ('payment', 'refund', 'adjustment', 'transfer')),
    credit_paise  bigint      NOT NULL CHECK (credit_paise >= 0),
    debit_paise   bigint      NOT NULL CHECK (debit_paise >= 0),
    fee_paise     bigint      NOT NULL CHECK (fee_paise >= 0),
    tax_paise     bigint      NOT NULL CHECK (tax_paise >= 0),
    our_paise     bigint,
    -- matched; missing_ours: Razorpay settled something we have no record of; missing_theirs: we have a captured
    -- payment no settlement carried; amount: the amounts differ; unexpected_fee: a fee above what we expect.
    match         text        NOT NULL
                      CHECK (match IN ('matched', 'missing_ours', 'missing_theirs', 'amount', 'unexpected_fee')),
    -- open; resolved: by an adjustment linked to it, as a known difference with a reason, or (missing_theirs) by a
    -- later settlement that carried it. Matched items are never open.
    state         text        NOT NULL CHECK (state IN ('matched', 'open', 'resolved')),
    resolution    text        CHECK (resolution IN ('adjustment', 'known_difference', 'settled_later')),
    adjustment_id uuid        REFERENCES billing.adjustments (id),
    reason        text        CHECK (reason IS NULL OR length(reason) BETWEEN 5 AND 500),
    resolved_by   text,
    resolved_at   timestamptz,
    found_at      timestamptz NOT NULL,
    version       bigint      NOT NULL CHECK (version >= 1),
    CONSTRAINT settlement_items_once UNIQUE NULLS NOT DISTINCT (settlement_id, type, entity_id),
    CONSTRAINT settlement_items_resolved_how CHECK ((state = 'resolved') = (resolution IS NOT NULL)),
    CONSTRAINT settlement_items_adjustment CHECK ((resolution = 'adjustment') = (adjustment_id IS NOT NULL))
);
CREATE INDEX settlement_items_open ON billing.settlement_items (found_at) WHERE state = 'open';
GRANT SELECT, INSERT, UPDATE ON billing.settlement_items TO idleo_application;

-- The weekly payouts as one batch (ADR 0055): a run is first a preview, each host's quote kept; it's released once,
-- by approval, and every item is paid as previewed. Runs from before batches were paid as they ran: released.
ALTER TABLE payouts.runs ADD COLUMN state text NOT NULL DEFAULT 'released'
    CHECK (state IN ('preview', 'released'));
ALTER TABLE payouts.runs ADD COLUMN version bigint NOT NULL DEFAULT 1 CHECK (version >= 1);
ALTER TABLE payouts.runs ADD COLUMN released_at timestamptz;
ALTER TABLE payouts.runs ADD COLUMN released_by text;
ALTER TABLE payouts.runs ADD COLUMN approval_id uuid UNIQUE;

-- Each host in a batch: what the preview found, or why they're left out, and the payout release made.
CREATE TABLE payouts.batch_items (
    run_id          uuid   NOT NULL REFERENCES payouts.runs (id),
    user_id         uuid   NOT NULL,
    gross_paise     bigint NOT NULL CHECK (gross_paise >= 0),
    recovered_paise bigint NOT NULL CHECK (recovered_paise >= 0),
    held_paise      bigint NOT NULL CHECK (held_paise >= 0),
    tds_paise       bigint NOT NULL CHECK (tds_paise >= 0),
    fee_paise       bigint NOT NULL CHECK (fee_paise >= 0),
    net_paise       bigint NOT NULL CHECK (net_paise >= 0),
    upi_masked      text,
    -- Why the host isn't paid in this batch (PayoutService.NotPaid), or null when they are.
    left_out        text   CHECK (left_out IS NULL OR left_out <> ''),
    payout_id       uuid   UNIQUE REFERENCES payouts.payouts (id),
    PRIMARY KEY (run_id, user_id)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON payouts.batch_items TO idleo_application;

INSERT INTO identity.admin_role_permissions (role_id, permission) VALUES
    ('owner', 'recon.read'), ('finance', 'recon.read'), ('auditor', 'recon.read'),
    ('owner', 'recon.resolve'), ('finance', 'recon.resolve');

-- An open settlement item is an inbox item (ADR 0053), to be resolved within 3 days, when it also alerts.
INSERT INTO queues.sla_targets (sla, first_minutes, resolve_minutes, pauses, legal, updated_at)
VALUES ('settlement', NULL, 4320, false, false, now());
