-- Billing: the double-entry ledger and the wallet cache (ADR 0005, ADR 0014).
-- The database itself keeps the money honest, even if application code has a
-- bug: transactions must balance, entries can never change, and no wallet can
-- go negative.

-- Every paisa moves through here. One row per entry; a transaction is the
-- entries sharing a txn_id, derived from what caused it, so a retry can't post
-- twice (technical design lines 160-172).
CREATE TABLE billing.ledger_entries (
    id           uuid        PRIMARY KEY,
    txn_id       uuid        NOT NULL,
    account      text        NOT NULL CHECK (account IN
                     ('renter_wallet', 'host_payable', 'platform_revenue', 'gateway_clearing', 'promotions')),
    owner_id     uuid,
    amount_paise bigint      NOT NULL CHECK (amount_paise > 0),
    direction    text        NOT NULL CHECK (direction IN ('debit', 'credit')),
    ref_type     text        NOT NULL,
    ref_id       uuid        NOT NULL,
    created_at   timestamptz NOT NULL,
    -- Owners for the renter and host accounts, never for the platform's.
    CONSTRAINT ledger_entries_owner CHECK (
        (account IN ('renter_wallet', 'host_payable')) = (owner_id IS NOT NULL)),
    CONSTRAINT ledger_entries_txn_account_owner_key UNIQUE NULLS NOT DISTINCT (txn_id, account, owner_id)
);

-- A renter's history, newest first, and the nightly check's per-owner sums.
CREATE INDEX ledger_entries_renter_history ON billing.ledger_entries (owner_id, created_at DESC, id DESC)
    WHERE account = 'renter_wallet';

-- Every transaction balances: checked at commit, once all its entries are in
-- (technical design line 192).
CREATE FUNCTION billing.check_ledger_transaction_balanced() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    net bigint;
BEGIN
    SELECT coalesce(sum(CASE direction WHEN 'debit' THEN amount_paise ELSE -amount_paise END), 0)
    INTO net
    FROM billing.ledger_entries
    WHERE txn_id = NEW.txn_id;
    IF net <> 0 THEN
        RAISE EXCEPTION 'ledger transaction % is unbalanced: debits minus credits is % paise', NEW.txn_id, net
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_transaction_balanced
    AFTER INSERT ON billing.ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION billing.check_ledger_transaction_balanced();

-- Append-only: a correction is a new, reversing transaction, never an edit.
CREATE FUNCTION billing.refuse_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON billing.ledger_entries
    FOR EACH ROW EXECUTE FUNCTION billing.refuse_change();
CREATE TRIGGER ledger_entries_no_truncate
    BEFORE TRUNCATE ON billing.ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION billing.refuse_change();

-- Each renter's balance and reservation: a cache of the ledger (technical
-- design line 106), created on the renter's first posting and changed only in
-- the same transaction as the entries behind it. A negative balance, or a
-- reservation above the balance, is a bug, so the database refuses it.
CREATE TABLE billing.wallets (
    user_id        uuid        PRIMARY KEY,
    balance_paise  bigint      NOT NULL,
    reserved_paise bigint      NOT NULL,
    version        bigint      NOT NULL,
    updated_at     timestamptz NOT NULL,
    CONSTRAINT wallets_never_negative CHECK (
        balance_paise >= 0 AND reserved_paise >= 0 AND reserved_paise <= balance_paise)
);

-- The audit record of every free credit grant and reversal (technical design
-- line 769): who, why, and the ledger transaction. Append-only, like the
-- ledger. user_id is the identity module's user id, with no foreign key across
-- modules.
CREATE TABLE billing.credit_grants (
    id              uuid        PRIMARY KEY,
    user_id         uuid        NOT NULL,
    amount_paise    bigint      NOT NULL CHECK (amount_paise > 0),
    reason          text        NOT NULL CHECK (reason <> ''),
    granted_by      text        NOT NULL CHECK (granted_by <> ''),
    idempotency_key text        NOT NULL UNIQUE,
    txn_id          uuid        NOT NULL UNIQUE,
    created_at      timestamptz NOT NULL
);

CREATE TABLE billing.credit_grant_reversals (
    id          uuid        PRIMARY KEY,
    grant_id    uuid        NOT NULL UNIQUE REFERENCES billing.credit_grants (id),
    reason      text        NOT NULL CHECK (reason <> ''),
    reversed_by text        NOT NULL CHECK (reversed_by <> ''),
    txn_id      uuid        NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL
);

CREATE TRIGGER credit_grants_append_only
    BEFORE UPDATE OR DELETE ON billing.credit_grants
    FOR EACH ROW EXECUTE FUNCTION billing.refuse_change();
CREATE TRIGGER credit_grants_no_truncate
    BEFORE TRUNCATE ON billing.credit_grants
    FOR EACH STATEMENT EXECUTE FUNCTION billing.refuse_change();
CREATE TRIGGER credit_grant_reversals_append_only
    BEFORE UPDATE OR DELETE ON billing.credit_grant_reversals
    FOR EACH ROW EXECUTE FUNCTION billing.refuse_change();
CREATE TRIGGER credit_grant_reversals_no_truncate
    BEFORE TRUNCATE ON billing.credit_grant_reversals
    FOR EACH STATEMENT EXECUTE FUNCTION billing.refuse_change();
