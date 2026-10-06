-- Host listings and activation (ADR 0015).

-- One price per workload type (technical design line 102); batch only for now.
CREATE TABLE hosts.price_listings (
    host_id             uuid        NOT NULL REFERENCES hosts.hosts (id),
    workload_type       text        NOT NULL CHECK (workload_type IN ('batch')),
    price_paise_per_min integer     NOT NULL CHECK (price_paise_per_min >= 1),
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    PRIMARY KEY (host_id, workload_type)
);

-- The host's own limits, set with its listing. One session at a time: one job
-- per GPU host keeps isolation and billing simple (ADR 0015).
ALTER TABLE hosts.hosts
    ADD COLUMN gpu_temp_cap_c          integer CHECK (gpu_temp_cap_c BETWEEN 60 AND 95),
    ADD COLUMN max_concurrent_sessions integer CHECK (max_concurrent_sessions = 1);

-- Every activation and suspension: who, why, from and to (technical design
-- line 775). Append-only.
CREATE TABLE hosts.status_changes (
    id          uuid        PRIMARY KEY,
    host_id     uuid        NOT NULL REFERENCES hosts.hosts (id),
    from_status text        NOT NULL,
    to_status   text        NOT NULL,
    reason      text        NOT NULL CHECK (reason <> ''),
    changed_by  text        NOT NULL CHECK (changed_by <> ''),
    created_at  timestamptz NOT NULL
);

CREATE INDEX status_changes_host ON hosts.status_changes (host_id, created_at);

CREATE FUNCTION hosts.refuse_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER status_changes_append_only
    BEFORE UPDATE OR DELETE ON hosts.status_changes
    FOR EACH ROW EXECUTE FUNCTION hosts.refuse_change();
CREATE TRIGGER status_changes_no_truncate
    BEFORE TRUNCATE ON hosts.status_changes
    FOR EACH STATEMENT EXECUTE FUNCTION hosts.refuse_change();
