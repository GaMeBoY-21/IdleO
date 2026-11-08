-- The kill switches and maintenance mode (ADR 0056): one row a flag, the truth every instance's cache reads.
CREATE SCHEMA IF NOT EXISTS flags;
GRANT USAGE ON SCHEMA flags TO idleo_application;

-- paused: what the flag names is stopped (for maintenance, the app is read-only). until: when it's expected to run
-- again, which it does by itself only when resume_automatically. reason, changed_by and changed_at: the last change's,
-- whose whole story is the audit log.
CREATE TABLE flags.flags (
    key text PRIMARY KEY,
    paused boolean NOT NULL DEFAULT false,
    until timestamptz,
    resume_automatically boolean NOT NULL DEFAULT false,
    reason text,
    changed_by uuid,
    changed_at timestamptz,
    version bigint NOT NULL DEFAULT 1,
    CONSTRAINT flags_resume_needs_a_time CHECK (NOT resume_automatically OR until IS NOT NULL),
    CONSTRAINT flags_running_holds_no_pause CHECK (paused OR (until IS NULL AND NOT resume_automatically))
);
CREATE TRIGGER flags_version BEFORE UPDATE ON flags.flags
    FOR EACH ROW EXECUTE FUNCTION identity.bump_version();
-- INSERT for a later release's new flag, which its migration adds.
GRANT SELECT, INSERT, UPDATE ON flags.flags TO idleo_application;

INSERT INTO flags.flags (key) VALUES
    ('bookings.new'), ('sessions.start'), ('payouts.send'), ('topups'), ('signups'), ('hosts.link'),
    ('images.custom'), ('maintenance');

-- Every admin sees them; the Owner, Operations, Finance and Trust & Safety may pause one, and ask to run it again.
INSERT INTO identity.admin_role_permissions (role_id, permission) VALUES
    ('owner', 'flags.read'), ('operations', 'flags.read'), ('support', 'flags.read'), ('trust_safety', 'flags.read'),
    ('finance', 'flags.read'), ('auditor', 'flags.read'),
    ('owner', 'flags.change'), ('operations', 'flags.change'), ('finance', 'flags.change'),
    ('trust_safety', 'flags.change');
