#!/usr/bin/env bash
# How long until an alert reaches a person (ADR 0036, docs/runbooks/monitoring.md "What it takes to hear"), measured
# on a stack of its own (compose project idleo-alerts, its own ports), with development's Prometheus, Alertmanager and
# Mailpit standing in for Grafana Cloud and an inbox:
#
#   1. No backup: the database stops archiving (WAL-G made unrunnable inside its container, as with a broken install)
#      and WAL waits; the clock runs until NoBackup's email arrives. Test-only, the rule's 10-minute window is
#      1 minute here (a copy of ops/alerts, never the files themselves), so the result is the pipeline's own delay;
#      production's is that plus 9 minutes.
#   2. Control plane down: its process killed outright (SIGKILL, a crash), and the clock runs until ControlPlaneSilent's
#      email arrives.
#
#   scripts/ops/alert-drill.sh           both, then the stack is taken down
#   scripts/ops/alert-drill.sh --keep    leave the stack up afterwards (Grafana on :13300, Mailpit on :18325)
set -euo pipefail

cd "$(dirname "$0")/../.."
root="$(pwd)"
keep=false
[[ "${1:-}" == --keep ]] && keep=true

http_port=18280
grpc_port=19643
sign_in_port=18299
storage_port=19300
mail_smtp_port=11225
mail_web_port=18325
web_port=13200
grafana_port=13300
prometheus_port=19390
alertmanager_port=19393
registry_port=15200
compose_project=idleo-alerts
out="$root/logs/alert-drill"
state="$out/state"
log() { printf 'alert-drill: %s\n' "$*"; }
fail() {
  printf 'alert-drill: FAIL: %s\n' "$*" >&2
  exit 1
}
mkdir -p "$out" "$state"
# shellcheck source=scripts/lib/stack.sh
. "$root/scripts/lib/stack.sh"
for port in "$http_port" "$grpc_port" "$sign_in_port" "$storage_port" "$mail_smtp_port" "$mail_web_port" \
  "$grafana_port" "$prometheus_port" "$alertmanager_port" "$registry_port"; do
  port_in_use "$port" && fail "port $port is in use; is another alert drill running? (docker compose -p $compose_project ps)"
done
"$root/control-plane/scripts/dev-ca.sh" >>"$out/dev-ca.log" 2>&1 || fail "the development CA couldn't be made"
"$root/ops/backup/dev-key.sh" >>"$out/dev-key.log" 2>&1 || fail "the development backup key couldn't be made"

compose() { COMPOSE_PROJECT_NAME="$compose_project" docker compose -f "$root/control-plane/compose.yaml" --profile observability "$@"; }
database="$compose_project-postgres-1"
mailpit="http://localhost:$mail_web_port/api/v1"
prometheus="http://localhost:$prometheus_port/api/v1"
sql() { docker exec -i "$database" psql -X -v ON_ERROR_STOP=1 -U controlplane -d controlplane -At -c "$1"; }

teardown() {
  if [[ -n "${control_plane_pid:-}" ]]; then
    kill -KILL -- "-$control_plane_pid" 2>/dev/null || true
  fi
  compose down --volumes --remove-orphans >>"$out/compose.log" 2>&1 || true
}
trap 'if ! $keep; then teardown; fi' EXIT
teardown

# The rules as they ship, but for NoBackup's archiving window: 1 minute instead of 10, test-only.
rules="$state/alerts"
rm -rf "$rules" && mkdir -p "$rules"
cp "$root"/ops/alerts/*.yaml "$rules/"
sed -i.orig 's/max(backups_wal_lag_seconds{job="control-plane"}) > 600/max(backups_wal_lag_seconds{job="control-plane"}) > 60/' \
  "$rules/backups.yaml" && rm -f "$rules/backups.yaml.orig"
grep -q '> 60$' "$rules/backups.yaml" || fail "couldn't shorten NoBackup's window in the copy"

stack_control_plane_settings() {
  export IDENTITY_INVITE_ONLY=false SPRING_DOCKER_COMPOSE_LIFECYCLE_MANAGEMENT=start-only
  export COMPOSE_PROFILES=observability SPRING_DOCKER_COMPOSE_PROFILES_ACTIVE=observability LOCAL_OBSERVABILITY=true
  export LOCAL_GRAFANA_PORT="$grafana_port" LOCAL_PROMETHEUS_PORT="$prometheus_port"
  export LOCAL_ALERTMANAGER_PORT="$alertmanager_port" LOCAL_REGISTRY_PORT="$registry_port" LOCAL_ALERTS_DIR="$rules"
  export LOCAL_BACKUP_PREFIX="s3://idleo-backups/alert-drill" LOCAL_BACKUP_PUBLIC_KEY="$root/private/backup-dev/backup.pub.asc"
  export BACKUPS_STORAGE_ENDPOINT="http://localhost:$storage_port" BACKUPS_STORAGE_BUCKET=idleo-backups
  export BACKUPS_STORAGE_PREFIX=alert-drill BACKUPS_STORAGE_REGION=us-east-1
  export BACKUPS_STORAGE_ACCESS_KEY_ID=local-storage-root BACKUPS_STORAGE_SECRET_ACCESS_KEY=local-only-not-a-secret
}
log "starting a stack of its own (logs/alert-drill/control-plane.log)"
stack_start_control_plane
stack_await_control_plane 600
"$root/ops/backup/backup-now.sh" --container "$database" >>"$out/backup.log" 2>&1 || fail "the first full backup failed"

# Quiet first: the control plane's meters in Prometheus, nothing firing, an empty inbox.
firing() { curl -sf "$prometheus/alerts" | python3 -c 'import json, sys
print(" ".join(sorted(a["labels"]["alertname"] for a in json.load(sys.stdin)["data"]["alerts"] if a["state"] == "firing")))'; }
deadline=$((SECONDS + 300))
until curl -sf "$prometheus/query" --data-urlencode 'query=count(backups_readable{job="control-plane"} == 1)' | grep -q '"value"' &&
  [[ -z "$(firing)" ]]; do
  ((SECONDS < deadline)) || fail "the stack didn't settle: firing $(firing)"
  sleep 5
done
curl -sf -X DELETE "$mailpit/messages" >/dev/null

# Waits for the alert's email; prints the seconds since $2 (a time in ms).
await_email() {
  local alert="$1" since="$2" deadline=$((SECONDS + 900))
  until curl -sf "$mailpit/search?query=$(python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "subject:\"[FIRING] $alert\"")" |
    python3 -c 'import json, sys; sys.exit(0 if json.load(sys.stdin)["messages_count"] else 1)'; do
    ((SECONDS < deadline)) || fail "no $alert email within 15 minutes"
    sleep 2
  done
  seconds_since "$since"
}

# 1. No backup: archiving stops.
log "stopping the database's archiving"
started="$(now_ms)"
# WAL-G can't run, as with a broken install: archive_command is set on the server's command line, which ALTER SYSTEM
# can't change, so the binary is what's broken, never storage (the watch must still read the bucket).
docker exec -u root "$database" chmod 000 /usr/local/bin/wal-g
sql "create table if not exists public.alert_drill (n int); insert into alert_drill select generate_series(1, 1000)" >/dev/null
sql "select pg_switch_wal()" >/dev/null
no_backup="$(await_email NoBackup "$started")"
log "NoBackup reached the inbox $no_backup s after archiving stopped (1-minute test window)"
docker exec -u root "$database" chmod 755 /usr/local/bin/wal-g

# 2. The control plane dies.
log "killing the control plane"
started="$(now_ms)"
kill -KILL -- "-$control_plane_pid"
control_plane_pid=""
silent="$(await_email ControlPlaneSilent "$started")"
log "ControlPlaneSilent reached the inbox $silent s after the control plane died"

printf '\nalert-drill: time from the failure to the alert email in the inbox\n'
printf '  %-38s %8s\n' "control plane down (ControlPlaneSilent)" "$silent s"
printf '  %-38s %8s  (production: + 540 s, the 10-minute window)\n' "archiving stopped (NoBackup, 1-min window)" "$no_backup s"
