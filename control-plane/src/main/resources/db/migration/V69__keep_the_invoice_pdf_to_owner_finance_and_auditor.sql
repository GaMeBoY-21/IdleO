-- The invoice PDF carries the buyer's name and details whole, so it has a permission of its own (ADR 0051): Support
-- reads invoices masked, and only the Owner, Finance and the Auditor take the document.
INSERT INTO identity.admin_role_permissions (role_id, permission)
SELECT role_id, 'invoices.pdf.read' FROM unnest(ARRAY['owner', 'finance', 'auditor']) AS role_id;
