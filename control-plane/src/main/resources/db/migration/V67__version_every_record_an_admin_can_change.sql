-- Safe admin writes (ADR 0051): every record an admin can change has a version, which an action's If-Match must name, so
-- an action on a record that changed since it was read is refused with a 412. A trigger bumps it, so no writer of this
-- release or the one before can forget. A writer that already bumps it isn't counted twice. For records that also change
-- on their own (a host's heartbeat, a session's lease), only what an admin's decision rests on counts.
CREATE FUNCTION identity.bump_version() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.version = OLD.version THEN
        NEW.version := OLD.version + 1;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER users_version BEFORE UPDATE ON identity.users
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status OR OLD.suspension_cause IS DISTINCT FROM NEW.suspension_cause)
    EXECUTE FUNCTION identity.bump_version();

CREATE TRIGGER hosts_version BEFORE UPDATE ON hosts.hosts
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION identity.bump_version();

CREATE TRIGGER sessions_version BEFORE UPDATE ON sessions.sessions
    FOR EACH ROW WHEN (OLD.state IS DISTINCT FROM NEW.state)
    EXECUTE FUNCTION identity.bump_version();

ALTER TABLE metering.review_minutes ADD COLUMN version bigint NOT NULL DEFAULT 1;
CREATE TRIGGER review_minutes_version BEFORE UPDATE ON metering.review_minutes
    FOR EACH ROW WHEN (OLD.* IS DISTINCT FROM NEW.*)
    EXECUTE FUNCTION identity.bump_version();

ALTER TABLE billing.credit_refunds ADD COLUMN version bigint NOT NULL DEFAULT 1;
CREATE TRIGGER credit_refunds_version BEFORE UPDATE ON billing.credit_refunds
    FOR EACH ROW WHEN (OLD.* IS DISTINCT FROM NEW.*)
    EXECUTE FUNCTION identity.bump_version();

ALTER TABLE billing.payment_mismatches ADD COLUMN version bigint NOT NULL DEFAULT 1;
CREATE TRIGGER payment_mismatches_version BEFORE UPDATE ON billing.payment_mismatches
    FOR EACH ROW WHEN (OLD.state IS DISTINCT FROM NEW.state OR OLD.detail IS DISTINCT FROM NEW.detail)
    EXECUTE FUNCTION identity.bump_version();

ALTER TABLE billing.clawbacks ADD COLUMN version bigint NOT NULL DEFAULT 1;
CREATE TRIGGER clawbacks_version BEFORE UPDATE ON billing.clawbacks
    FOR EACH ROW WHEN (OLD.* IS DISTINCT FROM NEW.*)
    EXECUTE FUNCTION identity.bump_version();

ALTER TABLE payouts.mismatches ADD COLUMN version bigint NOT NULL DEFAULT 1;
CREATE TRIGGER mismatches_version BEFORE UPDATE ON payouts.mismatches
    FOR EACH ROW WHEN (OLD.state IS DISTINCT FROM NEW.state OR OLD.detail IS DISTINCT FROM NEW.detail)
    EXECUTE FUNCTION identity.bump_version();

ALTER TABLE payouts.pans ADD COLUMN version bigint NOT NULL DEFAULT 1;
CREATE TRIGGER pans_version BEFORE UPDATE ON payouts.pans
    FOR EACH ROW WHEN (OLD.* IS DISTINCT FROM NEW.*)
    EXECUTE FUNCTION identity.bump_version();

ALTER TABLE payouts.methods ADD COLUMN version bigint NOT NULL DEFAULT 1;
CREATE TRIGGER methods_version BEFORE UPDATE ON payouts.methods
    FOR EACH ROW WHEN (OLD.* IS DISTINCT FROM NEW.*)
    EXECUTE FUNCTION identity.bump_version();

ALTER TABLE images.images ADD COLUMN version bigint NOT NULL DEFAULT 1;
CREATE TRIGGER images_version BEFORE UPDATE ON images.images
    FOR EACH ROW WHEN (OLD.state IS DISTINCT FROM NEW.state OR OLD.digest IS DISTINCT FROM NEW.digest OR OLD.reason IS DISTINCT FROM NEW.reason)
    EXECUTE FUNCTION identity.bump_version();

ALTER TABLE trust.abuse_items ADD COLUMN version bigint NOT NULL DEFAULT 1;
CREATE TRIGGER abuse_items_version BEFORE UPDATE ON trust.abuse_items
    FOR EACH ROW WHEN (OLD.* IS DISTINCT FROM NEW.*)
    EXECUTE FUNCTION identity.bump_version();

ALTER TABLE trust.blocklist_additions ADD COLUMN version bigint NOT NULL DEFAULT 1;
CREATE TRIGGER blocklist_additions_version BEFORE UPDATE ON trust.blocklist_additions
    FOR EACH ROW WHEN (OLD.* IS DISTINCT FROM NEW.*)
    EXECUTE FUNCTION identity.bump_version();

ALTER TABLE trust.disputes ADD COLUMN version bigint NOT NULL DEFAULT 1;
CREATE TRIGGER disputes_version BEFORE UPDATE ON trust.disputes
    FOR EACH ROW WHEN (OLD.* IS DISTINCT FROM NEW.*)
    EXECUTE FUNCTION identity.bump_version();

ALTER TABLE support.tickets ADD COLUMN version bigint NOT NULL DEFAULT 1;
CREATE TRIGGER tickets_version BEFORE UPDATE ON support.tickets
    FOR EACH ROW WHEN (OLD.* IS DISTINCT FROM NEW.*)
    EXECUTE FUNCTION identity.bump_version();
