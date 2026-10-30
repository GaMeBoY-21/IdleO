#!/bin/bash
# The database's two users (ADR 0045), made once, on a new volume's first start, by the official entrypoint (it runs
# /docker-entrypoint-initdb.d/ as the superuser). The cluster's database makes the same from its managed roles
# (deploy/helm/idleo/templates/database.yaml, ADR 0047); ApplicationRoleTests checks both:
#
#   idleo_migration   owns the database, and so every schema and table Flyway makes; only the migration Job logs in
#                     as it, and it can't create roles or read past its own database
#   idleo_app         the control plane's login: a member of idleo_application, which V61's grants give each module's
#                     tables, and of pg_monitor, so the backup watch sees WAL waiting to be archived; it owns nothing
#
# Their passwords come from IDLEO_MIGRATION_PASSWORD and IDLEO_APPLICATION_PASSWORD (the chart's database Secret). With
# neither set (compose.yaml, the tests' stacks) nothing is made, and the control plane runs as the superuser, as Flyway
# does there.
set -euo pipefail
if [[ -z "${IDLEO_MIGRATION_PASSWORD:-}" && -z "${IDLEO_APPLICATION_PASSWORD:-}" ]]; then
  exit 0
fi
: "${IDLEO_MIGRATION_PASSWORD:?needs IDLEO_APPLICATION_PASSWORD's partner, IDLEO_MIGRATION_PASSWORD}"
: "${IDLEO_APPLICATION_PASSWORD:?needs IDLEO_MIGRATION_PASSWORD's partner, IDLEO_APPLICATION_PASSWORD}"
# The passwords reach psql as variables, quoted by psql itself (:'name'), never pasted into the SQL.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v database="$POSTGRES_DB" -v migration_password="$IDLEO_MIGRATION_PASSWORD" \
  -v application_password="$IDLEO_APPLICATION_PASSWORD" <<'SQL'
CREATE ROLE idleo_migration LOGIN PASSWORD :'migration_password';
ALTER DATABASE :"database" OWNER TO idleo_migration;
CREATE ROLE idleo_application NOLOGIN;
CREATE ROLE idleo_app LOGIN PASSWORD :'application_password' IN ROLE idleo_application, pg_monitor;
SQL
