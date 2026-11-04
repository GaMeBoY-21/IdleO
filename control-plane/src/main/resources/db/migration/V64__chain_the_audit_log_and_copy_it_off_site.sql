-- A tamper-evident audit log (ADR 0051). Each row stores the SHA-256 of the previous row's hash and its own canonical
-- content, worked out here by a trigger, so every writer of any release is chained the same way and none can skip it.
-- Each row also says where the act came from: the request, the admin session, the address and browser, and the approval.
ALTER TABLE identity.audit_log
    ADD COLUMN seq              bigint,
    ADD COLUMN prev_hash        bytea,
    ADD COLUMN hash             bytea,
    ADD COLUMN request_id       text,
    ADD COLUMN admin_session_id uuid,
    ADD COLUMN ip               inet,
    ADD COLUMN user_agent       text,
    ADD COLUMN approval_id      uuid;

-- The row's content, in one fixed order. Hashes are worked out from these bytes, here and by anyone checking a copy.
CREATE FUNCTION identity.audit_row_content(r identity.audit_log) RETURNS bytea
    LANGUAGE sql STABLE
    SET search_path = pg_catalog
AS $$
    SELECT convert_to(json_build_array(
        r.seq, r.id, r.actor_kind, r.actor_user_id, r.actor_name, r.action, r.target_type, r.target_id, r.reason,
        r.before::text, r.after::text, (extract(epoch FROM r.created_at) * 1000000)::bigint, r.request_id,
        r.admin_session_id, host(r.ip), r.user_agent, r.approval_id)::text, 'UTF8');
$$;

-- The newest row's place and hash. Writers wait for its lock, so the chain's order is the order they commit in. The
-- application may read it; only the trigger, which runs as the table's owner, changes it.
CREATE TABLE identity.audit_chain_head (
    one  boolean PRIMARY KEY DEFAULT true CHECK (one),
    seq  bigint  NOT NULL,
    hash bytea   NOT NULL
);
GRANT SELECT ON identity.audit_chain_head TO idleo_application;

-- Today's rows join the chain, oldest first, from a hash of 32 zero bytes. This one migration may change them.
GRANT UPDATE ON identity.audit_log TO CURRENT_USER;
ALTER TABLE identity.audit_log DISABLE TRIGGER audit_log_append_only;
DO $$
DECLARE
    row_now identity.audit_log;
    place   bigint := 0;
    head    bytea  := decode(repeat('00', 32), 'hex');
BEGIN
    FOR row_now IN SELECT * FROM identity.audit_log ORDER BY created_at, id LOOP
        place := place + 1;
        row_now.seq := place;
        row_now.prev_hash := head;
        head := sha256(row_now.prev_hash || identity.audit_row_content(row_now));
        UPDATE identity.audit_log SET seq = place, prev_hash = row_now.prev_hash, hash = head WHERE id = row_now.id;
    END LOOP;
    INSERT INTO identity.audit_chain_head (seq, hash) VALUES (place, head);
END
$$;
ALTER TABLE identity.audit_log ENABLE TRIGGER audit_log_append_only;
REVOKE UPDATE ON identity.audit_log FROM CURRENT_USER;

ALTER TABLE identity.audit_log
    ALTER COLUMN seq SET NOT NULL,
    ALTER COLUMN prev_hash SET NOT NULL,
    ALTER COLUMN hash SET NOT NULL;
CREATE UNIQUE INDEX audit_log_by_seq ON identity.audit_log (seq);
CREATE INDEX audit_log_by_request ON identity.audit_log (request_id) WHERE request_id IS NOT NULL;
CREATE INDEX audit_log_by_approval ON identity.audit_log (approval_id) WHERE approval_id IS NOT NULL;

CREATE FUNCTION identity.audit_chain() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog
AS $$
DECLARE
    head identity.audit_chain_head;
BEGIN
    SELECT * INTO STRICT head FROM identity.audit_chain_head FOR UPDATE;
    NEW.seq := head.seq + 1;
    NEW.prev_hash := head.hash;
    NEW.hash := sha256(NEW.prev_hash || identity.audit_row_content(NEW));
    UPDATE identity.audit_chain_head SET seq = NEW.seq, hash = NEW.hash;
    RETURN NEW;
END;
$$;

CREATE TRIGGER audit_log_chain
    BEFORE INSERT ON identity.audit_log
    FOR EACH ROW EXECUTE FUNCTION identity.audit_chain();

-- Every check of the chain, by the daily job or an admin's button.
CREATE TABLE identity.audit_chain_checks (
    id         uuid        PRIMARY KEY,
    trigger    text        NOT NULL CHECK (trigger <> ''),
    rows_read  bigint      NOT NULL,
    head_seq   bigint      NOT NULL,
    intact     boolean     NOT NULL,
    broken_seq bigint,
    detail     text,
    took_ms    bigint      NOT NULL,
    checked_at timestamptz NOT NULL
);
CREATE INDEX audit_chain_checks_newest ON identity.audit_chain_checks (checked_at DESC);
GRANT SELECT, INSERT ON identity.audit_chain_checks TO idleo_application;

-- Each India day's rows and the chain head, written as files to the off-site store.
CREATE TABLE identity.audit_offsite_copies (
    day        date        PRIMARY KEY,
    rows_held  bigint      NOT NULL,
    first_seq  bigint,
    last_seq   bigint      NOT NULL,
    head_hash  bytea       NOT NULL,
    object_key text        NOT NULL,
    sha256     text        NOT NULL,
    written_at timestamptz NOT NULL
);
GRANT SELECT, INSERT ON identity.audit_offsite_copies TO idleo_application;
