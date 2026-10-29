-- Two database users (ADR 0045, docs/runbooks/deployment.md): the migration user owns every schema and table and is the
-- only one that runs Flyway; the control plane serves requests as a login in the idleo_application role, which owns
-- nothing and can't change the schema. From this migration on, a migration that creates a table grants the role what
-- the table needs, in the same migration (ApplicationRoleTests fails on a table without its grants).
--
-- In a cluster the database's first start makes the role (ops/postgres/initdb), since the migration user may not
-- create roles; on a developer's database and in the tests Flyway runs as the database's superuser, and makes it here.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'idleo_application') THEN
        CREATE ROLE idleo_application NOLOGIN;
    END IF;
END
$$;

-- Each module's schema: its tables read and written, never truncated, and its sequences used. Flyway's own schema isn't
-- here: the application never reads the migrations' history.
DO $$
DECLARE
    module text;
BEGIN
    FOREACH module IN ARRAY ARRAY['modulith', 'identity', 'hosts', 'agentgateway', 'overlay', 'scheduling', 'sessions',
            'metering', 'billing', 'payouts', 'trust', 'notifications', 'privacy', 'support', 'images'] LOOP
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO idleo_application', module);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO idleo_application', module);
        EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO idleo_application', module);
    END LOOP;
END
$$;

-- The append-only tables, whose triggers refuse every change: written once and read, never changed or removed, so an
-- application that can't drop a trigger can't get around them either.
REVOKE UPDATE, DELETE ON
    identity.audit_log,
    hosts.status_changes,
    sessions.session_events,
    billing.ledger_entries,
    billing.credit_grants,
    billing.credit_grant_reversals,
    billing.reservation_changes,
    billing.reconciliation_runs,
    billing.reconciliation_mismatches,
    billing.payment_events,
    billing.invoices,
    payouts.events,
    privacy.erased_users
FROM idleo_application;

-- Next months' partitions of metering.usage_minutes (UsagePartitions, daily): made by this function, as its owner, so
-- the application calls it instead of creating tables. The name and bounds come from the month alone.
CREATE FUNCTION metering.ensure_usage_partition(month date) RETURNS void
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    first_day timestamp := date_trunc('month', month::timestamp);
    partition_name text := 'usage_minutes_' || to_char(first_day, 'YYYY_MM');
BEGIN
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS metering.%I PARTITION OF metering.usage_minutes FOR VALUES FROM (%L) TO (%L)',
        partition_name,
        first_day AT TIME ZONE 'UTC',
        (first_day + interval '1 month') AT TIME ZONE 'UTC');
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON metering.%I TO idleo_application', partition_name);
END
$$;
REVOKE EXECUTE ON FUNCTION metering.ensure_usage_partition(date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION metering.ensure_usage_partition(date) TO idleo_application;
