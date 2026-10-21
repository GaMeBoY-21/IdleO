#!/usr/bin/env bash
# The restore drill (ADR 0036, docs/runbooks/backups.md "The drill"): proves the backups restore, to the second, and
# measures what a loss costs. Each run, on a fresh stack of its own (compose project idleo-drill, its own ports):
#
#   1. real activity: an account with credit, a host with a fake GPU, a session charged and the host earning;
#   2. a full backup (ops/backup/backup-now.sh), then more activity;
#   3. a moment T, taken with every table locked so nothing commits across it, and each table's checksum at T;
#   4. more activity, then the database lost: killed (SIGKILL, as a dead disk or droplet) and its volume deleted;
#   5. a restore to T (ops/backup/restore.sh) into a new database: every table's checksum equals T's, so every ledger
#      entry, reservation, session and audit row up to T is there and nothing after it is; the money check
#      (ops/backup/verify-ledger.sh) passes on it, and fails once a wallet is changed behind the ledger's back;
#   6. a restore to the last archived WAL, which says how much was lost: a ticker commits a row every second, and the
#      newest row restored against the moment of the loss is the data lost.
#
# It prints, for each run: data lost, restore time, the full backup's size and the WAL archived. scripts/check-all.sh
# runs it once (--no-drill leaves it out).
#
#   ops/backup/drill.sh               one run
#   ops/backup/drill.sh --runs 3      three in a row, each on a fresh stack
#   ops/backup/drill.sh --keep        leave the last run's stack and restored databases up to look at
set -euo pipefail

cd "$(dirname "$0")/../.."
root="$(pwd)"
runs=1
keep=false
while (($#)); do
  case "$1" in
    --runs) runs="$2"; shift 2 ;;
    --keep) keep=true; shift ;;
    -h | --help) sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "drill: unknown option $1 (--help)" >&2; exit 2 ;;
  esac
done

# The stack's ports, apart from ./start.sh's and the end-to-end test's.
http_port=18180
grpc_port=19543
sign_in_port=18199
storage_port=19200
mail_smtp_port=11125
mail_web_port=18125
web_port=13100
compose_project=idleo-drill
operator_name=restore-drill
out="$root/logs/drill"
state="$out/state"
log() { printf 'drill: %s\n' "$*"; }
fail() {
  printf 'drill: FAIL: %s\n' "$*" >&2
  exit 1
}
mkdir -p "$out" "$state"
# shellcheck source=scripts/lib/stack.sh
. "$root/scripts/lib/stack.sh"

for port in "$http_port" "$grpc_port" "$sign_in_port" "$storage_port" "$mail_smtp_port" "$mail_web_port" 15100; do
  port_in_use "$port" && fail "port $port is in use; is another drill running? (docker compose -p $compose_project ps)"
done
"$root/control-plane/scripts/dev-ca.sh" >>"$out/dev-ca.log" 2>&1 || fail "the development CA couldn't be made"
"$root/ops/backup/dev-key.sh" >>"$out/dev-key.log" 2>&1 || fail "the development backup key couldn't be made"
image="$("$root/ops/backup/image.sh")"
log "building the agent (logs/drill/agent-build.log)"
stack_build_agent

compose() { COMPOSE_PROJECT_NAME="$compose_project" docker compose -f "$root/control-plane/compose.yaml" "$@"; }
database="$compose_project-postgres-1"
storage_root=(--user local-storage-root:local-only-not-a-secret --aws-sigv4 "aws:amz:us-east-1:s3")
psql_in() { docker exec -i "$1" psql -X -v ON_ERROR_STOP=1 -U controlplane -d controlplane -At "${@:2}"; }

teardown() {
  [[ -f "$state/agent-1.pid" ]] && kill -TERM "$(cat "$state/agent-1.pid")" 2>/dev/null || true
  rm -f "$state/agent-1.pid"
  if [[ -n "${control_plane_pid:-}" ]]; then
    kill -TERM -- "-$control_plane_pid" 2>/dev/null || true
    for _ in $(seq 1 60); do kill -0 "$control_plane_pid" 2>/dev/null || break; sleep 0.5; done
    kill -KILL -- "-$control_plane_pid" 2>/dev/null || true
    control_plane_pid=""
  fi
  for container in $(docker ps -a --format '{{.Names}}' | grep '^idleo-restore-drill-' || true); do
    docker rm -f "$container" >/dev/null 2>&1 || true
    docker volume rm "$container" >/dev/null 2>&1 || true
  done
  compose down --volumes --remove-orphans >>"$out/compose.log" 2>&1 || true
}
trap 'if ! $keep; then teardown; fi' EXIT

# The tables a restore must bring back exactly: every table but the drill's own ticker.
tables_sql="select format('%I.%I', schemaname, tablename) from pg_tables
  where schemaname not in ('pg_catalog', 'information_schema', 'drill') order by 1"

# Each table's checksum (and row count), in one statement; its rows in a fixed order whatever their physical order.
checksums_sql() {
  local first=true table
  for table in $(psql_in "$1" -c "$tables_sql"); do
    $first || printf ' union all '
    first=false
    printf "select '%s', count(*), md5(coalesce(string_agg(md5(t::text), '' order by md5(t::text)), '')) from %s t" \
      "$table" "$table"
  done
  printf ' order by 1;\n'
}

session_for() {
  # Books the 20-second smoke test as the renter, then waits for it to end and settle; prints its id.
  local id state deadline=$((SECONDS + 240))
  id="$(curl -sS --fail-with-body -X POST "$api/v1/sessions" -H "Authorization: Bearer $renter_token" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: drill-$run-$1" \
    -d '{"templateId": "cuda-smoke-test", "maxPricePaisePerMin": 20, "maxSpendPaise": 1000}' | json_get .id)" ||
    fail "booking session $1 failed"
  until state="$(curl -sf "$api/v1/sessions/$id" -H "Authorization: Bearer $renter_token" |
    python3 -c 'import json, sys; s = json.load(sys.stdin); print(s["state"] if s["settled"] else "")')" &&
    [[ "$state" == COMPLETED ]]; do
    [[ "$state" == FAILED || "$state" == NO_HOST_FREE ]] && fail "session $1 ended $state"
    ((SECONDS < deadline)) || fail "session $1 didn't complete and settle in 4 minutes"
    sleep 1
  done
  printf '%s' "$id"
}

grant() {
  ops grant-credits --user "$renter_id" --paise "$1" --reason "'Restore drill $2'" --key "drill-$run-$2" ||
    fail "grant-credits failed; see logs/drill/ops.log"
}

results=()
for run in $(seq 1 "$runs"); do
  log "run $run of $runs: a fresh stack"
  teardown
  rm -rf "$state" && mkdir -p "$state"
  prefix="drill-$(date -u +%Y%m%dT%H%M%S)"
  stack_control_plane_settings() {
    export IDENTITY_INVITE_ONLY=false
    # The containers outlive the control plane, as storage outlives a lost droplet: the restore reads it.
    export SPRING_DOCKER_COMPOSE_LIFECYCLE_MANAGEMENT=start-only
    # compose.yaml's development registry, which the drill doesn't use, on a port of its own.
    export LOCAL_REGISTRY_PORT=15100
    export SESSIONS_TEMPLATES_CATALOG="file:$root/templates/development.yaml"
    # The renter limits (ADR 0025) are lifted for the drill's own accounts: a new account books 2 sessions a day.
    export SESSIONS_LIMITS_NEW_PER_HOUR=1000 SESSIONS_LIMITS_NEW_PER_HOUR_FIRST_DAY=1000 SESSIONS_LIMITS_AT_ONCE=1000
    export SESSIONS_LEASES_TTL=10s SESSIONS_LEASES_RENEW_EVERY=3s SESSIONS_LEASES_TRANSIT_ALLOWANCE=2s
    export SESSIONS_LEASES_LAPSE_SWEEP_EVERY=1s METERING_TICK_EVERY=1s METERING_REVIEW_AFTER=20s
    # The database archives to this stack's MinIO, encrypted with the development key; the control plane watches it.
    export LOCAL_BACKUP_PREFIX="s3://idleo-backups/$prefix"
    export LOCAL_BACKUP_PUBLIC_KEY="$root/private/backup-dev/backup.pub.asc"
    export BACKUPS_STORAGE_ENDPOINT="http://localhost:$storage_port" BACKUPS_STORAGE_BUCKET=idleo-backups
    export BACKUPS_STORAGE_PREFIX="$prefix" BACKUPS_STORAGE_REGION=us-east-1
    export BACKUPS_STORAGE_ACCESS_KEY_ID=local-storage-root BACKUPS_STORAGE_SECRET_ACCESS_KEY=local-only-not-a-secret
  }
  stack_start_control_plane
  stack_await_control_plane 600
  stack_await_sign_in
  # The bucket exists once the control plane has made it; archiving retries until then.
  curl -sf -o /dev/null -X PUT "${storage_root[@]}" "http://localhost:$storage_port/idleo-backups" || true

  # The ticker: a row committed every second until the database is lost, by its own clock.
  psql_in "$database" >/dev/null <<'SQL'
create schema drill;
create table drill.ticks (at timestamptz not null);
create procedure drill.tick() language plpgsql as $$
begin
  loop
    insert into drill.ticks values (clock_timestamp());
    commit;
    perform pg_sleep(1);
  end loop;
end $$;
SQL
  docker exec -d "$database" psql -U controlplane -d controlplane -c 'call drill.tick()'

  # 1. Activity: a renter with credit, a host that earns.
  renter_token="$(stack_token "drill-renter")"
  renter_id="$(curl -sf "$api/v1/me" -H "Authorization: Bearer $renter_token" | json_get .id)"
  owner_token="$(stack_token "drill-owner")"
  host_id="$(stack_register_host "$owner_token" "Drill laptop" "drill-host-$run")"
  stack_agent_config 1
  stack_enrol_agent 1 "$(stack_enrolment_code "$owner_token" "$host_id")"
  curl -sf -X PUT "$api/v1/hosts/$host_id/listing" -H "Authorization: Bearer $owner_token" \
    -H 'Content-Type: application/json' \
    -d '{"batchPricePaisePerMin": 12, "gpuTempCapC": 85, "maxConcurrentSessions": 1, "keepFreeGb": 10}' >/dev/null ||
    fail "the host's price couldn't be set"
  stack_run_agent 1 --fake-gpu-model "RTX 4060 Laptop" --fake-gpu-vram-mb 8188 --fake-free-disk-mb 200000
  deadline=$((SECONDS + 60))
  until curl -sf "$api/v1/hosts/$host_id" -H "Authorization: Bearer $owner_token" | grep -q '"online":true'; do
    ((SECONDS < deadline)) || fail "the drill's host didn't come online; see logs/drill/agent-1.log"
    sleep 0.5
  done
  ops activate-host --host "$host_id" --reason "'Restore drill host'" || fail "activate-host failed"
  grant 5000 before-backup
  session_for 1 >/dev/null
  log "run $run: activity made; taking a full backup"

  # 2. A full backup, then more activity.
  backup_report="$("$root/ops/backup/backup-now.sh" --container "$database")" || fail "the full backup failed"
  log "run $run: ${backup_report%%$'\n'*}"
  grant 3000 after-backup
  session_for 2 >/dev/null

  # 3. T: every table locked (in share mode, so nothing commits across T), T read, every table's checksum taken.
  tables="$(psql_in "$database" -c "$tables_sql" | paste -sd, -)"
  at_t="$( (
    echo "begin;"
    echo "lock table $tables in share mode;"
    echo "select 'T=' || to_char(clock_timestamp() at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US') || '+00:00';"
    echo "select 'WAL=' || pg_walfile_name(pg_current_wal_lsn());"
    checksums_sql "$database"
    echo "commit;"
  ) | psql_in "$database")"
  target="$(sed -n 's/^T=//p' <<<"$at_t")"
  wal_at_t="$(sed -n 's/^WAL=//p' <<<"$at_t")"
  grep -v '^T=\|^WAL=\|^BEGIN\|^LOCK\|^COMMIT' <<<"$at_t" >"$state/checksums-at-t"
  [[ -n "$target" && -s "$state/checksums-at-t" ]] || fail "couldn't take T's checksums"
  log "run $run: T is $target, $(wc -l <"$state/checksums-at-t" | tr -d ' ') tables checksummed"

  # 4. More activity after T, then the loss.
  grant 2000 after-t
  late_session="$(session_for 3)"
  # T can be restored to once the WAL segment holding it is archived: within a minute (archive_timeout).
  deadline=$((SECONDS + 150))
  until [[ "$(psql_in "$database" -c "select coalesce(last_archived_wal, '') >= '$wal_at_t' from pg_stat_archiver")" == t ]]; do
    ((SECONDS < deadline)) || fail "the WAL holding T ($wal_at_t) wasn't archived in time"
    sleep 1
  done
  # A real loss comes at any moment of archiving's minute, not just after it caught up: a random wait, so what's lost
  # is measured as it would be.
  wait_before_loss=$((RANDOM % 60))
  log "run $run: losing the database in ${wait_before_loss} s"
  sleep "$wait_before_loss"
  lost_at="$(psql_in "$database" -c "select extract(epoch from clock_timestamp())")"
  docker kill "$database" >/dev/null
  # The droplet is gone: the agent and the control plane with it, and the database's disk.
  [[ -f "$state/agent-1.pid" ]] && kill -TERM "$(cat "$state/agent-1.pid")" 2>/dev/null || true
  kill -TERM -- "-$control_plane_pid" 2>/dev/null || true
  compose rm --stop --force --volumes postgres >>"$out/compose.log" 2>&1
  log "run $run: database lost"

  # 5. Restore to T, into a new database.
  export WALG_S3_PREFIX="s3://idleo-backups/$prefix" AWS_ENDPOINT=http://storage:9000 AWS_REGION=us-east-1
  export AWS_ACCESS_KEY_ID=local-storage-root AWS_SECRET_ACCESS_KEY=local-only-not-a-secret
  to_t="$("$root/ops/backup/restore.sh" --to "$target" --name "drill-t-$run" --network "${compose_project}_default" \
    --port 55441)" || fail "the restore to T failed"
  restore_seconds="$(sed -n 's/.* in \([0-9.]*\) s$/\1/p' <<<"$to_t")"
  restored="idleo-restore-drill-t-$run"
  checksums_sql "$restored" | psql_in "$restored" >"$state/checksums-restored"
  if ! diff -q "$state/checksums-at-t" "$state/checksums-restored" >/dev/null; then
    diff "$state/checksums-at-t" "$state/checksums-restored" | head -20 >&2
    fail "the database restored to T isn't the database at T"
  fi
  [[ "$(psql_in "$restored" -c "select count(*) from sessions.sessions where id = '$late_session'")" == 0 ]] ||
    fail "a session booked after T came back"
  "$root/ops/backup/verify-ledger.sh" --container "$restored" >"$state/verify-ledger.log" ||
    fail "the money check failed on the restored database: $(cat "$state/verify-ledger.log")"
  # The money check must catch a wallet changed behind the ledger's back.
  psql_in "$restored" -c "update billing.wallets set balance_paise = balance_paise + 1
    where user_id = (select user_id from billing.wallets order by user_id limit 1)" >/dev/null
  if "$root/ops/backup/verify-ledger.sh" --container "$restored" >"$state/verify-ledger-tampered.log" 2>&1; then
    fail "the money check passed a wallet that doesn't match its ledger"
  fi
  log "run $run: restored to T in $restore_seconds s; all $(wc -l <"$state/checksums-at-t" | tr -d ' ') tables match, the session after T is absent, the money check passes (and catches a changed wallet)"

  # 6. Restore to the last archived WAL: what was lost is the time from the newest tick restored to the loss.
  "$root/ops/backup/restore.sh" --to latest --name "drill-latest-$run" --network "${compose_project}_default" \
    --port 55442 >"$state/restore-latest.log" || fail "the restore to the last archived WAL failed"
  newest_tick="$(psql_in "idleo-restore-drill-latest-$run" -c "select extract(epoch from max(at)) from drill.ticks")"
  data_lost="$(perl -e 'printf "%.1f", $ARGV[0] - $ARGV[1]' "$lost_at" "$newest_tick")"

  sizes="$(curl -sf "${storage_root[@]}" "http://localhost:$storage_port/idleo-backups?list-type=2&prefix=$prefix/" |
    python3 -c 'import re, sys
text = sys.stdin.read()
keys = re.findall(r"<Key>([^<]+)</Key><LastModified>[^<]*</LastModified><ETag>[^<]*</ETag><Size>(\d+)</Size>", text)
base = sum(int(s) for k, s in keys if "/basebackups_005/" in k)
wal = sum(int(s) for k, s in keys if "/wal_005/" in k)
print("%.1f %.1f" % (base / 1e6, wal / 1e6))')"
  read -r backup_mb wal_mb <<<"$sizes"
  log "run $run: data lost ${data_lost} s, restore to T ${restore_seconds} s, full backup ${backup_mb} MB, WAL ${wal_mb} MB"
  results+=("$(printf '%-4s %12s %12s %16s %15s %10s' "$run" "${wait_before_loss} s" "${data_lost} s" "${restore_seconds} s" "${backup_mb} MB" "${wal_mb} MB")")
done

printf '\ndrill: every run restored to T exactly and passed the money check\n'
printf '  %-4s %12s %12s %16s %15s %10s\n' run "loss after" "data lost" "restore to T" "full backup" "WAL"
printf '  %s\n' "${results[@]}"
