-- Saved views of the console's lists (ADR 0052), and the indexes their orders need.
CREATE TABLE console.saved_views (
    id         uuid        PRIMARY KEY,
    owner_id   uuid        NOT NULL,
    list_name  text        NOT NULL,
    name       text        NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
    shared     boolean     NOT NULL,
    query      text        NOT NULL CHECK (length(query) <= 2000),
    created_at timestamptz NOT NULL
);
CREATE INDEX saved_views_by_list ON console.saved_views (list_name, owner_id);
CREATE INDEX saved_views_by_owner ON console.saved_views (owner_id);
GRANT SELECT, INSERT, DELETE ON console.saved_views TO idleo_application;

CREATE INDEX users_by_email_for_sorting ON identity.users ((coalesce(email, '')), id);
CREATE INDEX users_by_name_for_sorting ON identity.users ((coalesce(display_name, '')), id);
CREATE INDEX users_by_status ON identity.users (status, id);
CREATE INDEX hosts_by_name_for_sorting ON hosts.hosts (name, id);
CREATE INDEX hosts_by_status ON hosts.hosts (status, id);
CREATE INDEX sessions_by_state_for_sorting ON sessions.sessions (state, id);
CREATE INDEX sessions_by_state_changed ON sessions.sessions (state_changed_at, id);
CREATE INDEX topups_by_amount ON billing.topups (amount_paise, id);
CREATE INDEX topups_by_status ON billing.topups (status, id);
CREATE INDEX invoices_by_number_and_id ON billing.invoices (number, id);
CREATE INDEX invoices_by_issue ON billing.invoices (issued_at, id);
CREATE INDEX invoices_by_total ON billing.invoices (total_paise, id);
CREATE INDEX payouts_by_net ON payouts.payouts (net_paise, id);
CREATE INDEX payouts_by_status ON payouts.payouts (status, id);
CREATE INDEX credit_refunds_by_request ON billing.credit_refunds (requested_at, id);
CREATE INDEX chargebacks_by_receipt ON billing.chargebacks (received_at, id);
