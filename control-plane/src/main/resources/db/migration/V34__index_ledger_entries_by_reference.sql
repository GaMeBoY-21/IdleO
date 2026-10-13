-- What a session paid, and so how much of it a report can refund, is summed from the ledger entries that name it
-- (ADR 0028).
CREATE INDEX ledger_entries_by_ref ON billing.ledger_entries (ref_id);
