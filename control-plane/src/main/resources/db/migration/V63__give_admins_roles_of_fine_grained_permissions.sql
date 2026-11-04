-- Who may do what in the admin console (ADR 0051): a role is a named bundle of permissions, an admin holds any number
-- of them. The permissions themselves are AdminPermission in the code; every endpoint declares one, and
-- AdminPermissionMatrixTests holds the committed table of who can do what.
CREATE TABLE identity.admin_roles (
    id   text PRIMARY KEY CHECK (id ~ '^[a-z_]+$'),
    name text NOT NULL UNIQUE CHECK (name <> '')
);

CREATE TABLE identity.admin_role_permissions (
    role_id    text NOT NULL REFERENCES identity.admin_roles (id),
    permission text NOT NULL CHECK (permission ~ '^[a-z-]+(\.[a-z-]+)+$'),
    PRIMARY KEY (role_id, permission)
);

-- An admin's roles go with the admin: revoke-admin deletes them, as it does the second factor.
CREATE TABLE identity.admin_role_grants (
    user_id    uuid        NOT NULL REFERENCES identity.admins (user_id) ON DELETE CASCADE,
    role_id    text        NOT NULL REFERENCES identity.admin_roles (id),
    granted_by text        NOT NULL CHECK (granted_by <> ''),
    granted_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, role_id)
);

GRANT SELECT, INSERT ON identity.admin_roles, identity.admin_role_permissions TO idleo_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON identity.admin_role_grants TO idleo_application;

INSERT INTO identity.admin_roles (id, name) VALUES
    ('owner', 'Owner'),
    ('operations', 'Operations'),
    ('support', 'Support'),
    ('trust_safety', 'Trust & Safety'),
    ('finance', 'Finance'),
    ('auditor', 'Auditor');

INSERT INTO identity.admin_role_permissions (role_id, permission)
SELECT 'owner', permission FROM unnest(ARRAY[
    'dashboard.read', 'users.read', 'users.pii.reveal', 'users.suspend', 'users.unsuspend', 'privacy.read',
    'privacy.export', 'privacy.legal-order', 'hosts.read', 'hosts.activate', 'hosts.suspend', 'hosts.self-test',
    'hosts.certificates.revoke', 'sessions.read', 'sessions.log.read', 'sessions.stop', 'review.read', 'review.charge',
    'review.waive', 'money.read', 'money.adjust', 'payments.read', 'reconciliation.run', 'reconciliation.accept',
    'refunds.read', 'refunds.approve', 'refunds.decline', 'invoices.read', 'invoices.credit-note', 'clawbacks.read',
    'clawbacks.waive', 'payouts.read', 'payouts.run', 'payouts.release', 'verifications.read', 'verifications.decide',
    'images.read', 'images.block', 'images.unblock', 'abuse.read', 'abuse.decide', 'abuse.clear', 'abuse.blocklist',
    'disputes.read', 'disputes.decide', 'disputes.refund', 'ratings.read', 'tickets.read', 'tickets.reply',
    'invites.read', 'invites.create', 'notices.publish', 'audit.read', 'export.create'
]) AS permission;

INSERT INTO identity.admin_role_permissions (role_id, permission)
SELECT 'operations', permission FROM unnest(ARRAY[
    'dashboard.read', 'users.read', 'users.suspend', 'hosts.read', 'hosts.activate', 'hosts.suspend',
    'hosts.self-test', 'hosts.certificates.revoke', 'sessions.read', 'sessions.stop', 'review.read', 'review.waive',
    'images.read', 'images.block', 'ratings.read', 'tickets.read', 'invites.read', 'invites.create', 'notices.publish'
]) AS permission;

INSERT INTO identity.admin_role_permissions (role_id, permission)
SELECT 'support', permission FROM unnest(ARRAY[
    'dashboard.read', 'users.read', 'users.pii.reveal', 'privacy.read', 'privacy.export', 'hosts.read',
    'sessions.read', 'payments.read', 'refunds.read', 'invoices.read', 'disputes.read', 'disputes.decide',
    'ratings.read', 'tickets.read', 'tickets.reply', 'invites.read'
]) AS permission;

INSERT INTO identity.admin_role_permissions (role_id, permission)
SELECT 'trust_safety', permission FROM unnest(ARRAY[
    'dashboard.read', 'users.read', 'users.pii.reveal', 'users.suspend', 'users.unsuspend', 'privacy.read',
    'hosts.read', 'hosts.suspend', 'hosts.certificates.revoke', 'sessions.read', 'sessions.log.read', 'sessions.stop',
    'review.read', 'images.read', 'images.block', 'images.unblock', 'abuse.read', 'abuse.decide', 'abuse.clear',
    'abuse.blocklist', 'disputes.read', 'disputes.decide', 'ratings.read', 'tickets.read'
]) AS permission;

INSERT INTO identity.admin_role_permissions (role_id, permission)
SELECT 'finance', permission FROM unnest(ARRAY[
    'dashboard.read', 'users.read', 'users.pii.reveal', 'review.read', 'review.charge', 'review.waive', 'money.read',
    'money.adjust', 'payments.read', 'reconciliation.run', 'reconciliation.accept', 'refunds.read', 'refunds.approve',
    'refunds.decline', 'invoices.read', 'invoices.credit-note', 'clawbacks.read', 'clawbacks.waive', 'payouts.read',
    'payouts.run', 'payouts.release', 'verifications.read', 'verifications.decide', 'disputes.read',
    'disputes.refund', 'tickets.read', 'export.create'
]) AS permission;

-- Read-only: everything an admin can read, the audit log included, masked as for everyone. A session's log isn't here:
-- reading one is a recorded act with a reason, for judging a report, not for a review.
INSERT INTO identity.admin_role_permissions (role_id, permission)
SELECT 'auditor', permission FROM unnest(ARRAY[
    'dashboard.read', 'users.read', 'privacy.read', 'hosts.read', 'sessions.read', 'review.read', 'money.read',
    'payments.read', 'refunds.read', 'invoices.read', 'clawbacks.read', 'payouts.read', 'verifications.read',
    'images.read', 'abuse.read', 'disputes.read', 'ratings.read', 'tickets.read', 'invites.read', 'audit.read'
]) AS permission;

-- Everyone who is an admin today runs the whole console, and keeps doing so.
INSERT INTO identity.admin_role_grants (user_id, role_id, granted_by, granted_at)
SELECT user_id, 'owner', 'migration', now() FROM identity.admins;
