-- Credit in lots, and the accounts money coming in needs (ADR 0039).
--
-- A renter's credit is two accounts: renter_wallet holds free credit (grants, promotions and give-backs) and
-- renter_purchased what they bought. Each is kept in lots, so a refund of bought credit never pays out free credit as
-- cash, free credit can expire while bought credit never does, and every charge says which lots it drew from: an
-- entry on a renter account names its lot (lot_id), and a charge drawing from two lots of one account is two entries.
--
-- The new accounts:
--   renter_purchased       liability, owned by the renter: credit they bought
--   refunds_pending        liability, owned by the renter: bought credit on its way back to where it was paid from
--   disputes_frozen        liability, owned by the renter: credit frozen while a chargeback is open
--   tax_cgst, tax_sgst,
--   tax_igst               liability: GST owed
--   gst_on_unused_credit   contra-liability, raised by debits: GST paid on bought credit not spent yet (per_topup)
--   free_credit_expired    raised by credits: free credit that expired, the other side of what promotions cost
--   chargeback_losses      expense, raised by debits: a lost chargeback the renter's credit couldn't cover
-- gateway_clearing (ADR 0014) is Razorpay's: money Razorpay holds for us.

ALTER TABLE billing.ledger_entries DROP CONSTRAINT ledger_entries_account_check;
ALTER TABLE billing.ledger_entries ADD CONSTRAINT ledger_entries_account_check CHECK (account IN (
    'renter_wallet', 'renter_purchased', 'host_payable', 'platform_revenue', 'gateway_clearing', 'promotions',
    'refunds_pending', 'disputes_frozen', 'tax_cgst', 'tax_sgst', 'tax_igst', 'gst_on_unused_credit',
    'free_credit_expired', 'chargeback_losses'));
ALTER TABLE billing.ledger_entries DROP CONSTRAINT ledger_entries_owner;
ALTER TABLE billing.ledger_entries ADD CONSTRAINT ledger_entries_owner CHECK (
    (account IN ('renter_wallet', 'renter_purchased', 'host_payable', 'refunds_pending', 'disputes_frozen'))
        = (owner_id IS NOT NULL));

-- The lot an entry draws from or adds to: set on renter_wallet and renter_purchased entries from now on (the ledger
-- refuses one without), and on gst_on_unused_credit's, which follow the bought lot they were paid with. Entries from
-- before lots have none; the move below nets them to zero for every renter.
ALTER TABLE billing.ledger_entries ADD COLUMN lot_id uuid;
ALTER TABLE billing.ledger_entries DROP CONSTRAINT ledger_entries_txn_account_owner_key;
ALTER TABLE billing.ledger_entries ADD CONSTRAINT ledger_entries_txn_account_owner_lot_key
    UNIQUE NULLS NOT DISTINCT (txn_id, account, owner_id, lot_id);
CREATE INDEX ledger_entries_by_lot ON billing.ledger_entries (lot_id) WHERE lot_id IS NOT NULL;

-- The history index covered renter_wallet only; a renter's history is both their accounts now.
DROP INDEX billing.ledger_entries_renter_history;
CREATE INDEX ledger_entries_renter_history ON billing.ledger_entries (owner_id, created_at DESC, id DESC)
    WHERE account IN ('renter_wallet', 'renter_purchased');

-- One lot of credit: what it started at, what's left (a cache of its entries, kept by the ledger in the same
-- transaction), and when free credit expires (never, for bought credit and give-backs). Written only by the ledger.
CREATE TABLE billing.credit_lots (
    id              uuid        PRIMARY KEY,
    user_id         uuid        NOT NULL,
    kind            text        NOT NULL CHECK (kind IN ('free', 'purchased')),
    source_type     text        NOT NULL CHECK (source_type <> ''),
    source_id       uuid        NOT NULL,
    amount_paise    bigint      NOT NULL CHECK (amount_paise > 0),
    remaining_paise bigint      NOT NULL CHECK (remaining_paise >= 0 AND remaining_paise <= amount_paise),
    expires_at      timestamptz,
    created_at      timestamptz NOT NULL,
    CONSTRAINT credit_lots_bought_never_expire CHECK (kind = 'free' OR expires_at IS NULL),
    CONSTRAINT credit_lots_one_per_source UNIQUE (source_type, source_id)
);
CREATE INDEX credit_lots_spendable ON billing.credit_lots (user_id, kind, created_at) WHERE remaining_paise > 0;
CREATE INDEX credit_lots_expiring ON billing.credit_lots (expires_at) WHERE remaining_paise > 0 AND expires_at IS NOT NULL;

-- Today's free credit becomes one free lot per renter, expiring 90 days from now, so no tester's credit disappears
-- the day lots arrive: one balanced transaction a renter moves the balance from its lot-less entries into the lot.
INSERT INTO billing.credit_lots (id, user_id, kind, source_type, source_id, amount_paise, remaining_paise, expires_at,
                                 created_at)
SELECT md5('free-lot-migration:' || w.user_id)::uuid, w.user_id, 'free', 'free_lot_migration', w.user_id,
       w.balance_paise, w.balance_paise, now() + interval '90 days', now()
FROM billing.wallets w
WHERE w.balance_paise > 0;

INSERT INTO billing.ledger_entries (id, txn_id, account, owner_id, amount_paise, direction, ref_type, ref_id, created_at,
                                    lot_id)
SELECT md5('free-lot-migration-out:' || l.user_id)::uuid, md5('free-lot-migration-txn:' || l.user_id)::uuid,
       'renter_wallet', l.user_id, l.amount_paise, 'debit', 'free_lot_migration', l.id, now(), NULL
FROM billing.credit_lots l WHERE l.source_type = 'free_lot_migration'
UNION ALL
SELECT md5('free-lot-migration-in:' || l.user_id)::uuid, md5('free-lot-migration-txn:' || l.user_id)::uuid,
       'renter_wallet', l.user_id, l.amount_paise, 'credit', 'free_lot_migration', l.id, now(), l.id
FROM billing.credit_lots l WHERE l.source_type = 'free_lot_migration';

-- The nightly wallet check proves each lot equals its entries, too.
ALTER TABLE billing.reconciliation_mismatches DROP CONSTRAINT reconciliation_mismatches_kind_check;
ALTER TABLE billing.reconciliation_mismatches ADD CONSTRAINT reconciliation_mismatches_kind_check
    CHECK (kind IN ('balance', 'reserved', 'hold', 'lot'));

-- The ledger and its lots are the money records: the application's database user may add to them, never change or
-- remove them (the triggers refuse that too; this takes the right away as well).
REVOKE UPDATE, DELETE, TRUNCATE ON billing.ledger_entries FROM PUBLIC;
REVOKE UPDATE, DELETE, TRUNCATE ON billing.ledger_entries FROM CURRENT_USER;
REVOKE DELETE, TRUNCATE ON billing.credit_lots FROM PUBLIC;
REVOKE DELETE, TRUNCATE ON billing.credit_lots FROM CURRENT_USER;
