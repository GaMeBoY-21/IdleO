-- The wallet reconciliation's results (ADR 0014, ADR 0027), so the admin console can show the latest runs: each
-- complete run, nightly or by the reconcile-wallets command, with the ledger's totals and every mismatch it found.
-- Append-only, like the ledger they check.
CREATE TABLE billing.reconciliation_runs (
    id                   uuid        PRIMARY KEY,
    trigger              text        NOT NULL CHECK (trigger <> ''),
    finished_at          timestamptz NOT NULL,
    ledger_debits_paise  bigint      NOT NULL,
    ledger_credits_paise bigint      NOT NULL,
    mismatches           integer     NOT NULL CHECK (mismatches >= 0)
);

CREATE INDEX reconciliation_runs_newest ON billing.reconciliation_runs (finished_at DESC);

-- A wallet, reservation or hold that disagreed: the user, the cached or summed value, and what it should be.
CREATE TABLE billing.reconciliation_mismatches (
    run_id         uuid   NOT NULL REFERENCES billing.reconciliation_runs (id),
    position       integer NOT NULL CHECK (position >= 0),
    kind           text   NOT NULL CHECK (kind IN ('balance', 'reserved', 'hold')),
    user_id        uuid   NOT NULL,
    wallet_paise   bigint NOT NULL,
    expected_paise bigint NOT NULL,
    PRIMARY KEY (run_id, position)
);

CREATE TRIGGER reconciliation_runs_append_only
    BEFORE UPDATE OR DELETE ON billing.reconciliation_runs
    FOR EACH ROW EXECUTE FUNCTION billing.refuse_change();
CREATE TRIGGER reconciliation_runs_no_truncate
    BEFORE TRUNCATE ON billing.reconciliation_runs
    FOR EACH STATEMENT EXECUTE FUNCTION billing.refuse_change();
CREATE TRIGGER reconciliation_mismatches_append_only
    BEFORE UPDATE OR DELETE ON billing.reconciliation_mismatches
    FOR EACH ROW EXECUTE FUNCTION billing.refuse_change();
CREATE TRIGGER reconciliation_mismatches_no_truncate
    BEFORE TRUNCATE ON billing.reconciliation_mismatches
    FOR EACH STATEMENT EXECUTE FUNCTION billing.refuse_change();
