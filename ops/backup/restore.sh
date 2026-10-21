#!/usr/bin/env bash
# Restores the database from its backups into a NEW PostgreSQL, never over the live one (ADR 0036,
# docs/runbooks/backups.md "Restoring"): the newest full backup finished before the target, then the archived WAL
# replayed up to the target, in a container and volume of its own. It never archives, so it can't write into the
# backups it came from. Run ops/backup/verify-ledger.sh against it before anyone uses it.
#
#   ops/backup/restore.sh --to 2026-10-20T14:05:00+05:30   to that moment (ISO 8601, with its zone)
#   ops/backup/restore.sh --to latest                         to the last archived WAL
#
# Options: --name NAME (the container and volume, idleo-restore-<NAME>; default the time now), --port PORT (on this
# machine; default 55432), --key FILE (the backup key's private half; default private/backup-dev/backup.key.asc,
# development's), --network NETWORK (a Docker network the storage is on, for a local MinIO).
#
# Reads the backups' location from the environment, as the database's container has it: WALG_S3_PREFIX, AWS_ENDPOINT,
# AWS_REGION, AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY (a key that may read the bucket), and AWS_S3_FORCE_PATH_STYLE;
# WALG_PGP_KEY_PASSPHRASE when the key has one. Prints the container, its port, the backup it started from and how
# long the restore took. Exits 0 once the new database accepts connections, out of recovery.
set -euo pipefail
cd "$(dirname "$0")/../.."
root="$(pwd)"

target=""
name="$(date -u +%Y%m%dt%H%M%S)"
port=55432
key="$root/private/backup-dev/backup.key.asc"
network=""
while (($#)); do
  case "$1" in
    --to) target="$2"; shift 2 ;;
    --name) name="$2"; shift 2 ;;
    --port) port="$2"; shift 2 ;;
    --key) key="$2"; shift 2 ;;
    --network) network="$2"; shift 2 ;;
    -h | --help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "restore: unknown option $1 (--help)" >&2; exit 2 ;;
  esac
done
[[ -n "$target" ]] || { echo "restore: say where to restore to: --to '<time with zone>' or --to latest" >&2; exit 2; }
[[ -r "$key" ]] || { echo "restore: can't read the backup key's private half at $key" >&2; exit 2; }
for variable in WALG_S3_PREFIX AWS_ENDPOINT AWS_REGION AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY; do
  [[ -n "${!variable:-}" ]] || { echo "restore: set $variable (docs/runbooks/backups.md)" >&2; exit 2; }
done

container="idleo-restore-$name"
if docker ps -a --format '{{.Names}}' | grep -qx "$container"; then
  echo "restore: $container already exists; choose another --name, or remove it (docker rm -f $container; docker volume rm $container)" >&2
  exit 2
fi
image="$("$root/ops/backup/image.sh")"
started_ms="$(perl -MTime::HiRes=time -e 'printf "%d", time * 1000')"

walg_env=(-e WALG_S3_PREFIX -e AWS_ENDPOINT -e AWS_REGION -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY
  -e AWS_S3_FORCE_PATH_STYLE="${AWS_S3_FORCE_PATH_STYLE:-true}")
[[ -n "$network" ]] && walg_env+=(--network "$network")

# The newest full backup that finished before the target (or the newest of all, for latest).
listing="$(docker run --rm "${walg_env[@]}" --entrypoint wal-g "$image" backup-list --detail --json 2>/dev/null)" ||
  { echo "restore: can't list the backups at $WALG_S3_PREFIX; check the storage settings" >&2; exit 1; }
backup="$(TARGET="$target" python3 -c '
import datetime, json, os, re, sys
def moment(text):
    # Python before 3.11 takes only 3 or 6 digits after the second; WAL-G drops trailing zeros.
    text = re.sub(r"\.(\d+)", lambda m: "." + (m.group(1) + "000000")[:6], text.replace("Z", "+00:00"))
    return datetime.datetime.fromisoformat(text)
target = os.environ["TARGET"]
cutoff = None if target == "latest" else moment(target)
if cutoff is not None and cutoff.tzinfo is None:
    sys.exit("restore: give the time with its zone, for example 2026-10-20T14:05:00+05:30")
best = None
for b in json.loads(sys.stdin.read() or "[]"):
    finished = moment(b["finish_time"])
    if (cutoff is None or finished <= cutoff) and (best is None or finished > best[0]):
        best = (finished, b["backup_name"])
print(best[1] if best else "")
' <<<"$listing")"
[[ -n "$backup" ]] || { echo "restore: no full backup finished before $target" >&2; exit 3; }

# Inside the container, as postgres: fetch the backup and set the target, then run PostgreSQL without archiving. The
# image's own entrypoint is skipped, since it would archive whenever WALG_S3_PREFIX is set.
inside='
set -euo pipefail
chown postgres:postgres /var/lib/postgresql
gosu postgres bash -s <<"AS_POSTGRES"
set -euo pipefail
mkdir -p "$PGDATA" && chmod 700 "$PGDATA"
wal-g backup-fetch "$PGDATA" "$RESTORE_BACKUP"
{
  echo "restore_command = '"'"'wal-g wal-fetch %f %p'"'"'"
  echo "recovery_target_action = '"'"'promote'"'"'"
  echo "recovery_target_timeline = '"'"'latest'"'"'"
  if [[ "$RESTORE_TARGET" != latest ]]; then
    echo "recovery_target_time = '"'"'$RESTORE_TARGET'"'"'"
    echo "recovery_target_inclusive = on"
  fi
  echo "archive_mode = off"
} >> "$PGDATA/postgresql.auto.conf"
touch "$PGDATA/recovery.signal"
AS_POSTGRES
exec gosu postgres postgres -c archive_mode=off -c listen_addresses="*"
'
docker volume create "$container" >/dev/null
args=(--name "$container" -d -p "127.0.0.1:$port:5432" -v "$container:/var/lib/postgresql"
  -v "$key:/run/backup.key.asc:ro" -e WALG_PGP_KEY_PATH=/run/backup.key.asc "${walg_env[@]}"
  -e RESTORE_TARGET="$target" -e RESTORE_BACKUP="$backup")
[[ -n "${WALG_PGP_KEY_PASSPHRASE:-}" ]] && args+=(-e WALG_PGP_KEY_PASSPHRASE)
docker run "${args[@]}" --entrypoint bash "$image" -c "$inside" >/dev/null

# Ready once it has finished recovery and promoted: out of recovery, accepting connections.
deadline=$((SECONDS + ${RESTORE_TIMEOUT:-1800}))
until docker exec "$container" psql -U "${PGUSER:-controlplane}" -d "${PGDATABASE:-controlplane}" -Atc 'select pg_is_in_recovery()' 2>/dev/null | grep -qx f; do
  if [[ "$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null)" != true ]]; then
    echo "restore: the restored database stopped; its log:" >&2
    docker logs --tail 30 "$container" >&2
    exit 1
  fi
  ((SECONDS < deadline)) || { echo "restore: not ready in time; docker logs $container" >&2; exit 1; }
  sleep 1
done
took_ms=$(($(perl -MTime::HiRes=time -e 'printf "%d", time * 1000') - started_ms))
printf 'restore: %s is ready on 127.0.0.1:%s, from %s to %s, in %d.%03d s\n' \
  "$container" "$port" "$backup" "$target" $((took_ms / 1000)) $((took_ms % 1000))
