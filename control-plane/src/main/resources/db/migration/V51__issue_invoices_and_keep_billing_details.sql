-- Invoices, receipts and credit notes, and the renter's billing details (ADR 0039).

-- Where the renter is, for GST: the state decides CGST and SGST (ours) or IGST (another); a GSTIN and a business name
-- make it a business-to-business invoice.
CREATE TABLE billing.billing_details (
    user_id       uuid        PRIMARY KEY,
    state_code    text        NOT NULL CHECK (state_code ~ '^[0-9]{2}$'),
    gstin         text        CHECK (gstin IS NULL OR gstin ~ '^[0-9]{2}[A-Z0-9]{13}$'),
    business_name text        CHECK (business_name IS NULL OR length(business_name) BETWEEN 1 AND 200),
    updated_at    timestamptz NOT NULL
);

-- One gap-free sequence of document numbers a financial year (April to March), never reused: the counter row is
-- locked while a number is taken, in the same transaction as the document it numbers.
CREATE TABLE billing.invoice_counters (
    financial_year text   PRIMARY KEY CHECK (financial_year ~ '^[0-9]{4}-[0-9]{2}$'),
    last_number    bigint NOT NULL CHECK (last_number >= 0)
);

-- Every document issued: a tax invoice, a receipt (not a tax invoice: GST registration pending, or monthly_usage's
-- receipt for a top-up), a monthly usage invoice, or a credit note against one of them. The document as issued
-- (seller, buyer, lines, totals) is kept whole, so the PDF and the JSON can be made from it again exactly, for the
-- money-records period. Never changed or deleted: a cancelled invoice gets a credit note.
CREATE TABLE billing.invoices (
    id              uuid        PRIMARY KEY,
    number          text        NOT NULL UNIQUE CHECK (length(number) <= 16),
    financial_year  text        NOT NULL,
    sequence        bigint      NOT NULL CHECK (sequence > 0),
    kind            text        NOT NULL CHECK (kind IN ('tax_invoice', 'receipt', 'usage_invoice', 'credit_note')),
    user_id         uuid        NOT NULL,
    topup_id        uuid,
    credit_note_for uuid        REFERENCES billing.invoices (id),
    period_start    timestamptz,
    period_end      timestamptz,
    taxable_paise   bigint      NOT NULL CHECK (taxable_paise >= 0),
    cgst_paise      bigint      NOT NULL CHECK (cgst_paise >= 0),
    sgst_paise      bigint      NOT NULL CHECK (sgst_paise >= 0),
    igst_paise      bigint      NOT NULL CHECK (igst_paise >= 0),
    total_paise     bigint      NOT NULL CHECK (total_paise >= 0),
    document        jsonb       NOT NULL,
    issued_at       timestamptz NOT NULL,
    CONSTRAINT invoices_sequence_once UNIQUE (financial_year, sequence),
    CONSTRAINT invoices_one_per_topup_and_kind UNIQUE NULLS DISTINCT (topup_id, kind),
    CONSTRAINT invoices_credit_note_names_its_invoice CHECK ((kind = 'credit_note') = (credit_note_for IS NOT NULL))
);
CREATE INDEX invoices_by_user ON billing.invoices (user_id, issued_at DESC);
CREATE UNIQUE INDEX invoices_one_usage_a_month ON billing.invoices (user_id, period_start) WHERE kind = 'usage_invoice';

CREATE TRIGGER invoices_append_only
    BEFORE UPDATE OR DELETE ON billing.invoices
    FOR EACH ROW EXECUTE FUNCTION billing.refuse_change();
CREATE TRIGGER invoices_no_truncate
    BEFORE TRUNCATE ON billing.invoices
    FOR EACH STATEMENT EXECUTE FUNCTION billing.refuse_change();
REVOKE UPDATE, DELETE, TRUNCATE ON billing.invoices FROM PUBLIC;
REVOKE UPDATE, DELETE, TRUNCATE ON billing.invoices FROM CURRENT_USER;
