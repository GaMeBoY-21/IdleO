-- Money out (ADR 0040): hosts are paid weekly to a checked UPI ID through RazorpayX, with tax deducted at source, and
-- earnings refunded to a renter after they were paid out are recovered from what the host earns next.
--
-- The new accounts:
--   payouts_in_flight   liability, owned by the host's owner: earnings on their way to the owner's UPI ID; moved here
--                       from host_payable before RazorpayX is asked to pay
--   payouts_paid        raised by credits, owned by the owner: what has left our RazorpayX account for the owner; its
--                       balance is the sum of their processed payouts
--   tds_payable         liability: tax deducted at source from payouts, owed to the government
--   host_receivable     asset, raised by debits, owned by the owner: earnings refunded to a renter after they were paid
--                       out, beyond what the owner's balance covered; recovered from later earnings, never paid out
--   earnings_forfeited  raised by credits: earnings an owner gave up in writing when deleting the account without a
--                       payout method
-- host_payable stays the owner's machines' earnings not yet paid out (ADR 0023), owned by each machine.

ALTER TABLE billing.ledger_entries DROP CONSTRAINT ledger_entries_account_check;
ALTER TABLE billing.ledger_entries ADD CONSTRAINT ledger_entries_account_check CHECK (account IN (
    'renter_wallet', 'renter_purchased', 'host_payable', 'platform_revenue', 'gateway_clearing', 'promotions',
    'refunds_pending', 'disputes_frozen', 'tax_cgst', 'tax_sgst', 'tax_igst', 'gst_on_unused_credit',
    'free_credit_expired', 'chargeback_losses', 'payouts_in_flight', 'payouts_paid', 'tds_payable', 'host_receivable',
    'earnings_forfeited'));
ALTER TABLE billing.ledger_entries DROP CONSTRAINT ledger_entries_owner;
ALTER TABLE billing.ledger_entries ADD CONSTRAINT ledger_entries_owner CHECK (
    (account IN ('renter_wallet', 'renter_purchased', 'host_payable', 'refunds_pending', 'disputes_frozen',
                 'payouts_in_flight', 'payouts_paid', 'host_receivable'))
        = (owner_id IS NOT NULL));

-- The sessions each payout paid a machine for, written with the payout's posting, so a refund to a renter after the
-- money left is known for a clawback (ADR 0040). A payout that came back voids its rows: they're read with its state.
CREATE TABLE billing.payout_sessions (
    payout_id    uuid   NOT NULL,
    host_id      uuid   NOT NULL,
    session_id   uuid   NOT NULL,
    amount_paise bigint NOT NULL CHECK (amount_paise > 0),
    PRIMARY KEY (payout_id, host_id, session_id)
);
CREATE INDEX payout_sessions_by_session ON billing.payout_sessions (session_id, host_id);

-- Earnings refunded to a renter after they were paid out (ADR 0040): what the owner's balance covered at once, what it
-- didn't (owed: a host_receivable, recovered from later earnings before any payout), and what an admin waived into
-- chargeback_losses. One per refund and machine.
CREATE TABLE billing.clawbacks (
    id                 uuid        PRIMARY KEY,
    owner_id           uuid        NOT NULL,
    host_id            uuid        NOT NULL,
    session_id         uuid        NOT NULL,
    cause              text        NOT NULL CHECK (cause IN ('report_refund')),
    cause_txn_id       uuid        NOT NULL,
    amount_paise       bigint      NOT NULL CHECK (amount_paise > 0),
    from_balance_paise bigint      NOT NULL CHECK (from_balance_paise >= 0),
    owed_paise         bigint      NOT NULL CHECK (owed_paise >= 0),
    recovered_paise    bigint      NOT NULL DEFAULT 0 CHECK (recovered_paise >= 0),
    waived_paise       bigint      NOT NULL DEFAULT 0 CHECK (waived_paise >= 0),
    -- recovered: nothing owed any more; owed: some still to recover; waived: an admin wrote off what was owed.
    state              text        NOT NULL CHECK (state IN ('recovered', 'owed', 'waived')),
    created_at         timestamptz NOT NULL,
    settled_at         timestamptz,
    waived_by          text,
    waive_reason       text        CHECK (waive_reason IS NULL OR length(waive_reason) <= 500),
    UNIQUE (cause_txn_id, host_id),
    CONSTRAINT clawbacks_split CHECK (from_balance_paise + owed_paise = amount_paise),
    CONSTRAINT clawbacks_settled CHECK (recovered_paise + waived_paise <= owed_paise)
);
CREATE INDEX clawbacks_owed ON billing.clawbacks (owner_id, created_at) WHERE state = 'owed';
CREATE INDEX clawbacks_by_owner ON billing.clawbacks (owner_id, created_at DESC);

REVOKE DELETE, TRUNCATE ON billing.payout_sessions, billing.clawbacks FROM PUBLIC;
REVOKE DELETE, TRUNCATE ON billing.payout_sessions, billing.clawbacks FROM CURRENT_USER;

-- What the payouts module keeps (ADR 0040).

CREATE FUNCTION payouts.refuse_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'payouts.% is append-only', TG_TABLE_NAME;
END;
$$;

-- A person's PAN, sealed (AES-256-GCM under payouts.pan-key, the person's id as associated data) and shown only by its
-- last four characters; the name as on the PAN, which the UPI ID's registered name is matched against. Hosts give it
-- before their first payout, and renters before buying more than legal.payouts.renter-pan-above-paise in a financial
-- year. Kept for the money-records period: the tax filings need it.
CREATE TABLE payouts.pans (
    user_id         uuid        PRIMARY KEY,
    pan_sealed      bytea       NOT NULL,
    pan_last4       text        NOT NULL CHECK (length(pan_last4) = 4),
    name            text        NOT NULL CHECK (length(name) BETWEEN 1 AND 85),
    -- From the PAN's fourth character: P or H an individual (or a family), the rest a business.
    holder          text        NOT NULL CHECK (holder IN ('individual', 'business')),
    -- review: waiting for an admin; verified; rejected (with the reason the person reads).
    state           text        NOT NULL CHECK (state IN ('review', 'verified', 'rejected')),
    -- What the PanVerifier said: verified, name_mismatch, invalid, or not_checked (no provider yet).
    checked         text        NOT NULL CHECK (checked IN ('verified', 'name_mismatch', 'invalid', 'not_checked')),
    verifier        text        NOT NULL,
    submitted_at    timestamptz NOT NULL,
    decided_at      timestamptz,
    decided_by      text,
    reason          text        CHECK (reason IS NULL OR length(reason) <= 500)
);
CREATE INDEX pans_in_review ON payouts.pans (submitted_at) WHERE state = 'review';

-- A UPI ID a host gave, as RazorpayX checked it (Fund Account Validation): checking (asked, no answer yet), checked
-- (an answer the host hasn't confirmed), failed (RazorpayX couldn't check it), review (confirmed, the name match below
-- the threshold, waiting for an admin), active (where payouts go), suspect (a payout to it came back: payouts wait
-- until the host checks a UPI ID again), rejected, replaced (another took its place). The VPA and the registered name
-- are cleared on erasure; RazorpayX's ids stay.
CREATE TABLE payouts.methods (
    id               uuid        PRIMARY KEY,
    user_id          uuid        NOT NULL,
    vpa              text        CHECK (vpa IS NULL OR length(vpa) BETWEEN 3 AND 100),
    state            text        NOT NULL CHECK (state IN ('checking', 'checked', 'failed', 'review', 'active',
                                                           'suspect', 'rejected', 'replaced')),
    validation_id    text,
    fund_account_id  text,
    account_status   text,
    registered_name  text        CHECK (registered_name IS NULL OR length(registered_name) <= 200),
    name_match_score integer     CHECK (name_match_score IS NULL OR name_match_score BETWEEN 0 AND 100),
    failure          text        CHECK (failure IS NULL OR length(failure) <= 500),
    checked_at       timestamptz NOT NULL,
    confirmed_at     timestamptz,
    -- After a change of UPI ID, no payout before this (48 hours): a stolen account can't redirect earnings at once.
    hold_until       timestamptz,
    ended_at         timestamptz,
    decided_by       text,
    reason           text        CHECK (reason IS NULL OR length(reason) <= 500)
);
CREATE UNIQUE INDEX methods_one_in_use ON payouts.methods (user_id) WHERE state IN ('active', 'suspect');
CREATE UNIQUE INDEX methods_one_in_review ON payouts.methods (user_id) WHERE state = 'review';
CREATE INDEX methods_by_user ON payouts.methods (user_id, checked_at DESC);
CREATE INDEX methods_checking ON payouts.methods (checked_at) WHERE state = 'checking';
CREATE UNIQUE INDEX methods_by_validation ON payouts.methods (validation_id) WHERE validation_id IS NOT NULL;

-- Each payout run: the weekly one (once a payday, whichever instance claims it) or one an admin started.
CREATE TABLE payouts.runs (
    id           uuid        PRIMARY KEY,
    kind         text        NOT NULL CHECK (kind IN ('weekly', 'manual')),
    payday       date        NOT NULL,
    trigger      text        NOT NULL,
    started_at   timestamptz NOT NULL,
    finished_at  timestamptz,
    owners       integer     CHECK (owners IS NULL OR owners >= 0),
    payouts      integer     CHECK (payouts IS NULL OR payouts >= 0),
    total_paise  bigint      CHECK (total_paise IS NULL OR total_paise >= 0),
    failure      text
);
CREATE UNIQUE INDEX runs_one_weekly ON payouts.runs (payday) WHERE kind = 'weekly';
CREATE INDEX runs_newest ON payouts.runs (started_at DESC);

-- One payout to one owner: gross is what left their machines' earnings, tds what was deducted for the government, fee
-- what they paid towards RazorpayX's charge (0 while IdleO pays it), net what RazorpayX was asked to send. status is
-- RazorpayX's (created: ours, before RazorpayX has it). reference_id and X-Payout-Idempotency are both the id.
CREATE TABLE payouts.payouts (
    id                 uuid        PRIMARY KEY,
    user_id            uuid        NOT NULL,
    run_id             uuid        REFERENCES payouts.runs (id),
    kind               text        NOT NULL CHECK (kind IN ('weekly', 'retry', 'final')),
    method_id          uuid        NOT NULL REFERENCES payouts.methods (id),
    fund_account_id    text        NOT NULL,
    gross_paise        bigint      NOT NULL CHECK (gross_paise > 0),
    tds_paise          bigint      NOT NULL CHECK (tds_paise >= 0),
    fee_paise          bigint      NOT NULL CHECK (fee_paise >= 0),
    net_paise          bigint      NOT NULL CHECK (net_paise >= 100),
    -- The section and rate the deduction was made under (legal.tds), and the financial year it counts in (2026-27).
    tds_section        text        NOT NULL,
    tds_basis_points   integer     NOT NULL CHECK (tds_basis_points >= 0),
    financial_year     text        NOT NULL CHECK (financial_year ~ '^[0-9]{4}-[0-9]{2}$'),
    status             text        NOT NULL CHECK (status IN ('created', 'queued', 'pending', 'processing',
                                                              'processed', 'failed', 'rejected', 'cancelled',
                                                              'reversed')),
    razorpayx_id       text        UNIQUE,
    utr                text,
    -- RazorpayX's status_details: its reason code, source and words.
    status_reason      text,
    status_source      text,
    status_description text        CHECK (status_description IS NULL OR length(status_description) <= 500),
    -- The oldest earning the payout carried: the days from it to processed_at are the host's wait.
    earned_since       timestamptz,
    attempts           integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error         text        CHECK (last_error IS NULL OR length(last_error) <= 500),
    created_at         timestamptz NOT NULL,
    sent_at            timestamptz,
    processed_at       timestamptz,
    ended_at           timestamptz,
    updated_at         timestamptz NOT NULL,
    CONSTRAINT payouts_net CHECK (net_paise = gross_paise - tds_paise - fee_paise)
);
CREATE UNIQUE INDEX payouts_one_open ON payouts.payouts (user_id)
    WHERE status IN ('created', 'queued', 'pending', 'processing');
CREATE INDEX payouts_by_user ON payouts.payouts (user_id, created_at DESC);
CREATE INDEX payouts_unsettled ON payouts.payouts (updated_at)
    WHERE status IN ('created', 'queued', 'pending', 'processing');
CREATE INDEX payouts_by_day ON payouts.payouts (created_at);

-- Each RazorpayX webhook handled, by x-razorpay-event-id, so a repeat changes nothing; RazorpayX's ids only, never the
-- payload (it names the host's UPI ID).
CREATE TABLE payouts.events (
    event_id      text        PRIMARY KEY,
    event         text        NOT NULL,
    payout_id     text,
    validation_id text,
    received_at   timestamptz NOT NULL
);
CREATE TRIGGER events_append_only
    BEFORE UPDATE OR DELETE ON payouts.events
    FOR EACH ROW EXECUTE FUNCTION payouts.refuse_change();

-- RazorpayX against our payouts (ADR 0040): once a day, and when an admin asks, a day's payouts listed from RazorpayX
-- and compared one by one with ours; each disagreement an item kept once by its kind and subject.
CREATE TABLE payouts.reconciliation_runs (
    id          uuid        PRIMARY KEY,
    day         date        NOT NULL,
    trigger     text        NOT NULL,
    started_at  timestamptz NOT NULL,
    finished_at timestamptz,
    payouts     integer     CHECK (payouts IS NULL OR payouts >= 0),
    found       integer     CHECK (found IS NULL OR found >= 0),
    open        integer     CHECK (open IS NULL OR open >= 0),
    failure     text
);
CREATE UNIQUE INDEX reconciliation_runs_one_nightly ON payouts.reconciliation_runs (day) WHERE trigger = 'nightly';
CREATE INDEX reconciliation_runs_newest ON payouts.reconciliation_runs (started_at DESC);

CREATE TABLE payouts.mismatches (
    id            uuid        PRIMARY KEY,
    kind          text        NOT NULL CHECK (kind IN ('missing_ours', 'missing_theirs', 'amount', 'status')),
    -- RazorpayX's payout id, or ours when RazorpayX has none.
    subject       text        NOT NULL,
    payout_id     uuid        REFERENCES payouts.payouts (id),
    user_id       uuid,
    our_paise     bigint,
    their_paise   bigint,
    our_status    text,
    their_status  text,
    detail        text        NOT NULL CHECK (length(detail) <= 500),
    state         text        NOT NULL CHECK (state IN ('open', 'fixed', 'accepted')),
    first_run_id  uuid        NOT NULL REFERENCES payouts.reconciliation_runs (id),
    last_run_id   uuid        NOT NULL REFERENCES payouts.reconciliation_runs (id),
    found_at      timestamptz NOT NULL,
    last_found_at timestamptz NOT NULL,
    closed_at     timestamptz,
    closed_by     text,
    close_reason  text        CHECK (close_reason IS NULL OR length(close_reason) <= 500),
    UNIQUE (kind, subject)
);
CREATE INDEX mismatches_open ON payouts.mismatches (found_at) WHERE state = 'open';

-- The day before payday: RazorpayX's balance against what the run will pay. Short means payouts will queue.
CREATE TABLE payouts.balance_checks (
    id            uuid        PRIMARY KEY,
    payday        date        NOT NULL,
    checked_at    timestamptz NOT NULL,
    balance_paise bigint,
    due_paise     bigint      NOT NULL CHECK (due_paise >= 0),
    short         boolean     NOT NULL,
    failure       text
);
CREATE INDEX balance_checks_newest ON payouts.balance_checks (checked_at DESC);

-- A deletion waiting for its final payout: written when the erasure first looks (PersonalData.beforeErasure), so the
-- payout is made once, at once, outside the weekly run.
CREATE TABLE payouts.final_payouts (
    user_id      uuid        PRIMARY KEY,
    requested_at timestamptz NOT NULL
);

-- Earnings an owner gave up in writing, to delete the account without a payout method (ADR 0040). Their own words.
CREATE TABLE payouts.forfeits (
    id           uuid        PRIMARY KEY,
    user_id      uuid        NOT NULL,
    amount_paise bigint      NOT NULL CHECK (amount_paise > 0),
    statement    text        NOT NULL CHECK (length(statement) BETWEEN 1 AND 500),
    created_at   timestamptz NOT NULL
);
CREATE INDEX forfeits_by_user ON payouts.forfeits (user_id);

-- Idempotency-Key records for POST /v1/payout-details/upi-checks, scoped to the user.
CREATE TABLE payouts.idempotency_keys (
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

-- Money records: kept, never deleted.
REVOKE DELETE, TRUNCATE ON payouts.payouts, payouts.runs, payouts.mismatches, payouts.reconciliation_runs,
    payouts.forfeits, payouts.pans FROM PUBLIC;
REVOKE DELETE, TRUNCATE ON payouts.payouts, payouts.runs, payouts.mismatches, payouts.reconciliation_runs,
    payouts.forfeits, payouts.pans FROM CURRENT_USER;
