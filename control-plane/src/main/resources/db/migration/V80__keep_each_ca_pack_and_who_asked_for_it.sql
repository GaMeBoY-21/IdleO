-- The CA's monthly pack (ADR 0055): each one made, for which month, by whom, and its manifest's SHA-256, which the audit
-- log keeps too. The pack itself isn't kept: it holds whole PANs, and it's made again from the ledger when asked.
CREATE TABLE billing.ca_packs (
    id              uuid        PRIMARY KEY,
    month           date        NOT NULL CHECK (month = date_trunc('month', month)::date),
    files           integer     NOT NULL CHECK (files > 0),
    manifest_sha256 text        NOT NULL CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
    actor           text        NOT NULL CHECK (actor <> ''),
    approval_id     uuid        NOT NULL UNIQUE,
    created_at      timestamptz NOT NULL
);
CREATE INDEX ca_packs_by_month ON billing.ca_packs (month, created_at DESC);
CREATE TRIGGER ca_packs_append_only
    BEFORE UPDATE OR DELETE ON billing.ca_packs
    FOR EACH ROW EXECUTE FUNCTION billing.refuse_change();
CREATE TRIGGER ca_packs_no_truncate
    BEFORE TRUNCATE ON billing.ca_packs
    FOR EACH STATEMENT EXECUTE FUNCTION billing.refuse_change();
GRANT SELECT, INSERT ON billing.ca_packs TO idleo_application;

INSERT INTO identity.admin_role_permissions (role_id, permission) VALUES
    ('owner', 'tax.export'), ('finance', 'tax.export');
