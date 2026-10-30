#!/usr/bin/env bash
# A full backup of ./start.sh's database now (ADR 0036; WAL-G, the development stack's; the cluster's database is
# CloudNativePG's, ADR 0047), then backups older than 30 days deleted; runs ops/postgres/backup-full inside the
# database's container, which has the backup settings. Prints the backup's name, size and how long it took.
#
#   ops/backup/backup-now.sh                         the local stack's database (./start.sh)
#   ops/backup/backup-now.sh --container NAME        another stack's (scripts/ops/alert-drill.sh)
set -euo pipefail
container="${IDLEO_DATABASE_CONTAINER:-idleo-local-postgres-1}"
if [[ "${1:-}" == --container ]]; then container="$2"; fi
started=$(date +%s)
if ! output="$(docker exec -u postgres "$container" backup-full 2>&1)"; then
  echo "backup-now: the backup failed:" >&2
  tail -15 <<<"$output" >&2
  exit 1
fi
docker exec -u postgres "$container" wal-g backup-list --detail --json 2>/dev/null | python3 -c '
import json, sys
newest = max(json.load(sys.stdin), key=lambda b: b["finish_time"])
print("backup-now: %s finished %s, %.1f MB stored (%.1f MB of data)" % (
    newest["backup_name"], newest["finish_time"], newest["compressed_size"] / 1e6, newest["uncompressed_size"] / 1e6))'
echo "backup-now: took $(($(date +%s) - started)) s"
