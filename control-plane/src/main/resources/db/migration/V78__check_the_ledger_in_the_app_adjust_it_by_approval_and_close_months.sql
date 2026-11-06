-- Money operations (ADR 0055): the accounts reconciliation and adjustments need, the ledger's checks as functions the
-- application and ops/backup/verify-ledger.sh both call, manual adjustments, and closing a month.

-- Three new accounts, none of them owned:
--   gateway_fees      expense, raised by debits: Razorpay's fees on what it settles to us
--   gst_input_credit  asset, raised by debits: the GST charged on those fees, which we claim back
--   write_offs        expense, raised by debits: small differences written off by an approved adjustment
-- contract: ledger_entries_account_check is replaced by a wider list; the release before writes only accounts both lists allow, safe since 482b800e
ALTER TABLE billing.ledger_entries DROP CONSTRAINT ledger_entries_account_check;
ALTER TABLE billing.ledger_entries ADD CONSTRAINT ledger_entries_account_check CHECK (account IN (
    'renter_wallet', 'renter_purchased', 'host_payable', 'platform_revenue', 'gateway_clearing', 'promotions',
    'refunds_pending', 'disputes_frozen', 'tax_cgst', 'tax_sgst', 'tax_igst', 'gst_on_unused_credit',
    'free_credit_expired', 'chargeback_losses', 'payouts_in_flight', 'payouts_paid', 'tds_payable', 'host_receivable',
    'earnings_forfeited', 'gateway_fees', 'gst_input_credit', 'write_offs')) NOT VALID;
ALTER TABLE billing.ledger_entries VALIDATE CONSTRAINT ledger_entries_account_check;

-- An account's balance at a moment, and the trial balance, read the entries up to that moment.
CREATE INDEX ledger_entries_by_account_and_time ON billing.ledger_entries (account, created_at);

-- The ledger's checks (ops/backup/verify-ledger.sh's list): each problem is one finding, with what it's about and the
-- two figures that disagree. The nightly run, an admin's run and the restore check read these, and nothing else
-- restates them. They read the tables of metering and payouts as the restore check always has: the ledger's rules
-- reach into both (two witnesses, ADR 0017; paid equals processed, ADR 0040).
CREATE FUNCTION billing.ledger_check_names() RETURNS TABLE (check_number integer, name text)
    LANGUAGE sql IMMUTABLE AS $$
    VALUES (1, 'each transaction balances'),
           (2, 'all debits equal all credits'),
           (3, 'no wallet below zero or holding more than it has'),
           (4, 'every wallet equals its ledger entries'),
           (5, 'every wallet''s reserved amount equals its active holds'),
           (6, 'every hold equals its recorded changes'),
           (7, 'no host''s earnings below zero'),
           (8, 'every credit lot equals its entries and none is below zero'),
           (9, 'credit from before lots nets to zero'),
           (10, 'nothing frozen or refunding below zero'),
           (11, 'every paid top-up credited exactly once'),
           (12, 'every processed refund part debited exactly once'),
           (13, 'no payout in flight below zero'),
           (14, 'paid equals the sum of processed payouts'),
           (15, 'a receivable is only recovered, never paid out'),
           (16, 'no session charged past its spending cap'),
           (17, 'every billed minute had both witnesses')
$$;

-- subject is the transaction, person, lot, hold, top-up, refund part, session or minute the finding is about, or null
-- for the whole ledger; actual is what was found and expected what the rule wants.
CREATE FUNCTION billing.ledger_check_findings()
    RETURNS TABLE (check_number integer, subject uuid, actual bigint, expected bigint)
    LANGUAGE sql STABLE AS $$
    select 1, txn_id, sum(case direction when 'debit' then amount_paise else -amount_paise end), 0::bigint
    from billing.ledger_entries group by txn_id
    having sum(case direction when 'debit' then amount_paise else -amount_paise end) <> 0
    union all
    select 2, null::uuid, debits, credits from (
        select coalesce(sum(amount_paise) filter (where direction = 'debit'), 0) as debits,
               coalesce(sum(amount_paise) filter (where direction = 'credit'), 0) as credits
        from billing.ledger_entries) totals
    where debits <> credits
    union all
    select 3, user_id, balance_paise, reserved_paise from billing.wallets
    where balance_paise < 0 or reserved_paise < 0 or reserved_paise > balance_paise
    union all
    select 4, coalesce(w.user_id, l.owner_id), coalesce(w.balance_paise, 0), coalesce(l.paise, 0)
    from billing.wallets w
    full join (select owner_id, sum(case direction when 'credit' then amount_paise else -amount_paise end) as paise
               from billing.ledger_entries where account in ('renter_wallet', 'renter_purchased')
               group by owner_id) l on l.owner_id = w.user_id
    where coalesce(w.balance_paise, 0) <> coalesce(l.paise, 0)
    union all
    select 5, coalesce(w.user_id, h.user_id), coalesce(w.reserved_paise, 0), coalesce(h.paise, 0)
    from billing.wallets w
    full join (select user_id, sum(amount_paise) as paise from billing.reservations where status = 'active'
               group by user_id) h on h.user_id = w.user_id
    where coalesce(w.reserved_paise, 0) <> coalesce(h.paise, 0)
    union all
    select 6, r.user_id, coalesce(sum(c.after_paise - c.before_paise), 0),
           case when r.status = 'active' then r.amount_paise else 0 end
    from billing.reservations r left join billing.reservation_changes c on c.reservation_id = r.id
    group by r.id, r.user_id, r.status, r.amount_paise
    having coalesce(sum(c.after_paise - c.before_paise), 0)
           <> case when r.status = 'active' then r.amount_paise else 0 end
    union all
    select 7, owner_id, sum(case direction when 'credit' then amount_paise else -amount_paise end), 0::bigint
    from billing.ledger_entries where account = 'host_payable' group by owner_id
    having sum(case direction when 'credit' then amount_paise else -amount_paise end) < 0
    union all
    select 8, l.user_id, l.remaining_paise,
           coalesce(sum(case e.direction when 'credit' then e.amount_paise else -e.amount_paise end), 0)
    from billing.credit_lots l
    left join billing.ledger_entries e on e.lot_id = l.id and e.account in ('renter_wallet', 'renter_purchased')
    group by l.id, l.user_id, l.remaining_paise
    having l.remaining_paise < 0
        or l.remaining_paise <> coalesce(sum(case e.direction when 'credit' then e.amount_paise
                                                              else -e.amount_paise end), 0)
    union all
    select 9, owner_id, sum(case direction when 'credit' then amount_paise else -amount_paise end), 0::bigint
    from billing.ledger_entries where account in ('renter_wallet', 'renter_purchased') and lot_id is null
    group by owner_id
    having sum(case direction when 'credit' then amount_paise else -amount_paise end) <> 0
    union all
    select 10, owner_id, sum(case direction when 'credit' then amount_paise else -amount_paise end), 0::bigint
    from billing.ledger_entries where account in ('disputes_frozen', 'refunds_pending') group by owner_id, account
    having sum(case direction when 'credit' then amount_paise else -amount_paise end) < 0
    union all
    select 11, t.id, (select count(*) from billing.ledger_entries e
                      where e.ref_type = 'topup' and e.ref_id = t.id and e.account = 'renter_purchased'
                        and e.direction = 'credit'), 1::bigint
    from billing.topups t
    where t.status = 'paid' and (select count(*) from billing.ledger_entries e
                                 where e.ref_type = 'topup' and e.ref_id = t.id and e.account = 'renter_purchased'
                                   and e.direction = 'credit') <> 1
    union all
    select 12, p.id, (select count(*) from billing.ledger_entries e
                      where e.ref_type = 'credit_refund_processed' and e.ref_id = p.id
                        and e.account = 'refunds_pending' and e.direction = 'debit'), 1::bigint
    from billing.refund_parts p
    where p.status = 'processed' and (select count(*) from billing.ledger_entries e
                                      where e.ref_type = 'credit_refund_processed' and e.ref_id = p.id
                                        and e.account = 'refunds_pending' and e.direction = 'debit') <> 1
    union all
    select 13, owner_id, sum(case direction when 'credit' then amount_paise else -amount_paise end), 0::bigint
    from billing.ledger_entries where account = 'payouts_in_flight' group by owner_id
    having sum(case direction when 'credit' then amount_paise else -amount_paise end) < 0
    union all
    select 14, coalesce(paid.owner_id, processed.user_id), coalesce(paid.paise, 0), coalesce(processed.paise, 0)
    from (select owner_id, sum(case direction when 'credit' then amount_paise else -amount_paise end) as paise
          from billing.ledger_entries where account = 'payouts_paid' group by owner_id) paid
    full join (select user_id, sum(net_paise) as paise from payouts.payouts where status = 'processed'
               group by user_id) processed on processed.user_id = paid.owner_id
    where coalesce(paid.paise, 0) <> coalesce(processed.paise, 0)
    union all
    select 15, txn_id, amount_paise, 0::bigint from billing.ledger_entries
    where account = 'host_receivable' and ref_type in ('payout', 'payout_paid', 'payout_returned', 'payout_reversed')
    union all
    select 15, owner_id, sum(case direction when 'debit' then amount_paise else -amount_paise end), 0::bigint
    from billing.ledger_entries where account = 'host_receivable' group by owner_id
    having sum(case direction when 'debit' then amount_paise else -amount_paise end) < 0
    union all
    select 16, m.session_id,
           (select coalesce(sum(amount_paise), 0) from metering.settlements s where s.session_id = m.session_id)
           - (select coalesce(sum(refunded_paise), 0) from metering.waivers w where w.session_id = m.session_id),
           m.max_spend_paise
    from metering.metered_sessions m
    where (select coalesce(sum(amount_paise), 0) from metering.settlements s where s.session_id = m.session_id)
        - (select coalesce(sum(refunded_paise), 0) from metering.waivers w where w.session_id = m.session_id)
        > m.max_spend_paise
    union all
    select 17, session_id, 1::bigint, 0::bigint from metering.usage_minutes
    where billed and not (agent_reported and lease_alive and agent_attempt = lease_attempt)
$$;

-- Each check and how many findings it has: zero is a pass.
CREATE FUNCTION billing.ledger_checks() RETURNS TABLE (check_number integer, name text, found bigint)
    LANGUAGE sql STABLE AS $$
    select n.check_number, n.name, count(f.check_number)
    from billing.ledger_check_names() n
    left join billing.ledger_check_findings() f on f.check_number = n.check_number
    group by n.check_number, n.name
    order by n.check_number
$$;

GRANT EXECUTE ON FUNCTION billing.ledger_check_names() TO idleo_application;
GRANT EXECUTE ON FUNCTION billing.ledger_check_findings() TO idleo_application;
GRANT EXECUTE ON FUNCTION billing.ledger_checks() TO idleo_application;

-- Every check's count in each reconciliation run, beside the wallets' mismatches the run already keeps.
CREATE TABLE billing.reconciliation_checks (
    run_id   uuid    NOT NULL REFERENCES billing.reconciliation_runs (id),
    position integer NOT NULL CHECK (position > 0),
    name     text    NOT NULL CHECK (name <> ''),
    found    bigint  NOT NULL CHECK (found >= 0),
    PRIMARY KEY (run_id, position)
);
CREATE TRIGGER reconciliation_checks_append_only
    BEFORE UPDATE OR DELETE ON billing.reconciliation_checks
    FOR EACH ROW EXECUTE FUNCTION billing.refuse_change();
CREATE TRIGGER reconciliation_checks_no_truncate
    BEFORE TRUNCATE ON billing.reconciliation_checks
    FOR EACH STATEMENT EXECUTE FUNCTION billing.refuse_change();
GRANT SELECT, INSERT ON billing.reconciliation_checks TO idleo_application;

-- A manual adjustment: a journal entry another admin approved (ADR 0051), posted as one ledger transaction named by
-- its approval. Its legs are that transaction's entries; nothing here restates them. A reversal is an adjustment too,
-- naming the one it reverses; corrects_month names a closed month the correction belongs to.
CREATE TABLE billing.adjustments (
    id             uuid        PRIMARY KEY,
    template       text        NOT NULL
                       CHECK (template IN ('journal', 'goodwill_credit', 'fee_correction', 'write_off', 'reversal')),
    reason         text        NOT NULL CHECK (length(reason) BETWEEN 5 AND 500),
    reference      text        NOT NULL CHECK (length(reference) BETWEEN 1 AND 200),
    corrects_month date        CHECK (corrects_month = date_trunc('month', corrects_month)::date),
    reverses_id    uuid        UNIQUE REFERENCES billing.adjustments (id),
    approval_id    uuid        NOT NULL UNIQUE,
    txn_id         uuid        NOT NULL UNIQUE,
    requested_by   text        NOT NULL CHECK (requested_by <> ''),
    created_at     timestamptz NOT NULL,
    CONSTRAINT adjustments_reversal_names_one CHECK ((template = 'reversal') = (reverses_id IS NOT NULL))
);
CREATE INDEX adjustments_newest ON billing.adjustments (created_at DESC, id DESC);
CREATE TRIGGER adjustments_append_only
    BEFORE UPDATE OR DELETE ON billing.adjustments
    FOR EACH ROW EXECUTE FUNCTION billing.refuse_change();
CREATE TRIGGER adjustments_no_truncate
    BEFORE TRUNCATE ON billing.adjustments
    FOR EACH STATEMENT EXECUTE FUNCTION billing.refuse_change();
GRANT SELECT, INSERT ON billing.adjustments TO idleo_application;

-- Closed months, in India's time. A month with no row is open. Closing and reopening are each an event, kept for good.
CREATE TABLE billing.periods (
    month      date        PRIMARY KEY CHECK (month = date_trunc('month', month)::date),
    state      text        NOT NULL CHECK (state IN ('open', 'closed')),
    version    bigint      NOT NULL CHECK (version >= 0),
    updated_at timestamptz NOT NULL
);
GRANT SELECT, INSERT, UPDATE ON billing.periods TO idleo_application;

CREATE TABLE billing.period_events (
    id          uuid        PRIMARY KEY,
    month       date        NOT NULL REFERENCES billing.periods (month),
    action      text        NOT NULL CHECK (action IN ('closed', 'reopened')),
    reason      text        NOT NULL CHECK (length(reason) BETWEEN 5 AND 500),
    actor       text        NOT NULL CHECK (actor <> ''),
    approval_id uuid        NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL
);
CREATE INDEX period_events_by_month ON billing.period_events (month, created_at);
CREATE TRIGGER period_events_append_only
    BEFORE UPDATE OR DELETE ON billing.period_events
    FOR EACH ROW EXECUTE FUNCTION billing.refuse_change();
CREATE TRIGGER period_events_no_truncate
    BEFORE TRUNCATE ON billing.period_events
    FOR EACH STATEMENT EXECUTE FUNCTION billing.refuse_change();
GRANT SELECT, INSERT ON billing.period_events TO idleo_application;

-- The database refuses an entry dated in a closed month, whatever the application does: a correction is an
-- adjustment in an open month that names the closed one.
CREATE FUNCTION billing.refuse_entries_in_closed_months() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    entry_month date := date_trunc('month', NEW.created_at AT TIME ZONE 'Asia/Kolkata')::date;
BEGIN
    IF EXISTS (SELECT 1 FROM billing.periods p WHERE p.month = entry_month AND p.state = 'closed') THEN
        RAISE EXCEPTION 'ledger entries dated in % are refused: the month is closed', to_char(entry_month, 'YYYY-MM')
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ledger_entries_open_months_only
    BEFORE INSERT ON billing.ledger_entries
    FOR EACH ROW EXECUTE FUNCTION billing.refuse_entries_in_closed_months();

-- Reading the ledger and closing a month: Finance and the Auditor read it; only the Owner closes or reopens a month.
INSERT INTO identity.admin_role_permissions (role_id, permission) VALUES
    ('owner', 'ledger.read'), ('finance', 'ledger.read'), ('auditor', 'ledger.read'),
    ('owner', 'periods.close');
