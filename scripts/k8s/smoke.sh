#!/usr/bin/env bash
# The proof that the Mac's cluster works as production will (ADR 0045), against the cluster up.sh made, by plain Helm
# or under Flux (--gitops, where the control plane that serves is Flagger's primary):
#
#   1. the website and the app load over HTTPS, with their security headers, and plain HTTP redirects to HTTPS;
#   2. the control plane's readiness is UP through the ingress;
#   3. the database is CloudNativePG's primary and standby, streaming; the migration Job ran as the migration user, the
#      application user can't create a table, and the S1 privilege test (ApplicationRoleTests) passes against it;
#   4. a made-up X-Forwarded-For from outside is counted under the address the ingress saw, never the made-up one;
#   5. a fake-GPU agent outside the cluster enrols and connects over mutual TLS, straight to the control plane, and its
#      host shows online;
#   6. a booking with the development runner runs to COMPLETED;
#   7. a rolling restart of the control plane loses no agent: told to reconnect, it's back within its backoff.
#
# Logs: private/k8s/logs/ (smoke.log, smoke-agent-1.log). It leaves the cluster running.
set -euo pipefail
. "$(dirname "$0")/../lib/quiet.sh"
quiet_entry k8s-smoke "$@"
set -- ${QUIET_ARGS[@]+"${QUIET_ARGS[@]}"}
log() { printf 'smoke: %s\n' "$*"; }
fail() { printf 'smoke: FAIL: %s\n' "$*" >&2; exit 1; }
pass() { printf 'smoke: PASS: %s\n' "$*"; summary+=("$*"); }
. "$(dirname "$0")/lib.sh"
cd "$k8s_root"
root="$k8s_root"
summary=()
cluster_exists || fail "there's no cluster; scripts/k8s/up.sh makes it"

# The stack's shared steps (scripts/lib/stack.sh), pointed at the cluster: its API and sign-in server, the agents'
# port, and this run's folder for the agent's config, keys and log.
out="$k8s_state/logs"
state="$k8s_state/smoke"
api="$api_url"
sign_in="$sign_in_url"
grpc_port="$agents_port"
operator_name=k8s-smoke
# The agents' CA, from the local Secrets (scripts/k8s/secrets.sh writes it).
agent_ca_root="$k8s_state/agent-ca.pem"
mkdir -p "$out" "$state"
# shellcheck source=scripts/lib/stack.sh
. "$root/scripts/lib/stack.sh"
# curl trusts the cluster's own CA for its HTTPS addresses.
export CURL_CA_BUNDLE="$local_ca/ca.pem"
ops() { OPERATOR="$operator_name" scripts/k8s/ops.sh "$@" >>"$out/smoke.log" 2>&1; }
in_namespace() { kubectl -n "$namespace" "$@"; }

agent_pid=""
cleanup() { [[ -n "$agent_pid" ]] && kill -TERM "$agent_pid" 2>/dev/null || true; }
trap cleanup EXIT

# 1. The website and the app over HTTPS, each with its own security policy (the builds' headers.json), and HTTP sent on.
site_headers="$(curl -sf -D - -o /dev/null "$site_url/")" || fail "the website didn't load at $site_url/"
grep -qi '^content-security-policy:' <<<"$site_headers" || fail "the website has no security policy: $site_headers"
app_headers="$(curl -sf -D - -o /dev/null "$app_url/sign-in/")" || fail "the app didn't load at $app_url/sign-in/"
grep -qi "^content-security-policy:.*connect-src[^;]*$api_url" <<<"$app_headers" ||
  fail "the app's policy doesn't allow its API: $app_headers"
redirect="$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' "http://app.idleo.localhost:$plain_http_port/sign-in/" || true)"
[[ "$redirect" == "301 $app_url/sign-in/" ]] ||
  fail "plain HTTP wasn't sent to HTTPS: $redirect"
pass "the website and the app load over HTTPS with their security headers; HTTP redirects ($redirect)"

# 1b. The local CA vouches for this cluster's names only: a certificate it signs for another site is refused.
openssl_bin="$(openssl3)"
constraint_check="$(mktemp -d)"
leaf() {
  "$openssl_bin" req -new -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout "$constraint_check/$1.key" \
    -subj "/CN=$1" -addext "subjectAltName=DNS:$1" -out "$constraint_check/$1.csr" 2>/dev/null
  "$openssl_bin" x509 -req -in "$constraint_check/$1.csr" -CA "$local_ca/ca.pem" -CAkey "$local_ca/ca.key" \
    -days 1 -copy_extensions copy -out "$constraint_check/$1.pem" 2>/dev/null
  "$openssl_bin" verify -CAfile "$local_ca/ca.pem" -purpose sslserver "$constraint_check/$1.pem" 2>&1
}
leaf app.idleo.localhost | grep -q ': OK$' || fail "the local CA can't vouch for app.idleo.localhost"
refused="$(leaf www.example.com || true)"
grep -q 'permitted subtree violation' <<<"$refused" || fail "the local CA could vouch for www.example.com: $refused"
rm -rf "$constraint_check"
pass "the local CA's name constraints: app.idleo.localhost verifies, www.example.com is refused (permitted subtree violation)"

# 2. Readiness through the ingress: the database and the CA.
readiness="$(curl -sf "$api/actuator/health/readiness")" || fail "readiness didn't answer at $api/actuator/health/readiness"
grep -q '"status":"UP"' <<<"$readiness" || fail "readiness isn't UP: $readiness"
pass "readiness is UP: $readiness"

# 3. The database: a primary and a streaming standby (ADR 0047). Two users: every migration installed by the migration
#    user, the tables its, and the application user refused a table of its own; then the privilege test itself.
primary="$(in_namespace get cluster idleo-database -o jsonpath='{.status.currentPrimary}')"
[[ -n "$primary" ]] || fail "the database has no primary: kubectl -n $namespace describe cluster idleo-database"
psql_superuser() { in_namespace exec "$primary" -c postgres -- psql -U postgres -d controlplane -tAX -c "$1"; }
streaming="$(psql_superuser "select count(*) from pg_stat_replication where state = 'streaming'")"
[[ "$streaming" == 1 ]] || fail "the primary $primary has $streaming standbys streaming, not 1"
installed_by="$(psql_superuser "select string_agg(distinct installed_by, ',') from flyway.flyway_schema_history")"
[[ "$installed_by" == idleo_migration ]] || fail "the migrations were installed by $installed_by, not idleo_migration"
owners="$(psql_superuser "select string_agg(distinct pg_get_userbyid(c.relowner), ',') from pg_class c
  join pg_namespace n on n.oid = c.relnamespace where n.nspname in ('identity', 'billing', 'metering', 'sessions')")"
[[ "$owners" == idleo_migration ]] || fail "the tables are owned by $owners, not idleo_migration"
version="$(psql_superuser "select max(version::int) from flyway.flyway_schema_history where success")"
# Every attempt's log: a first install's first attempts may have found the database still starting.
migration_log="$(in_namespace logs -l batch.kubernetes.io/job-name=idleo-migrate --tail=-1)"
grep -q "The database is at version $version" <<<"$migration_log" ||
  fail "the migration Job's log doesn't say it brought the database to version $version"
application_password="$(sed -n 's/^IDLEO_APPLICATION_PASSWORD=//p' "$k8s_state/database.env")"
refused="$(in_namespace exec "$primary" -c postgres -- env PGPASSWORD="$application_password" \
  psql -h 127.0.0.1 -U idleo_app -d controlplane -tAX -c 'create table metering.smoke (id int)' 2>&1 || true)"
grep -q 'permission denied' <<<"$refused" || fail "the application user could create a table: $refused"
pass "the migration Job brought the database to V$version as idleo_migration, which owns the tables; idleo_app can't create one"
# The S1 privilege test, against this database's users as CloudNativePG made them, through a port-forward to the
# primary's Service.
migration_password="$(sed -n 's/^IDLEO_MIGRATION_PASSWORD=//p' "$k8s_state/database.env")"
# kubectl's port-forward ends when a connection through it is reset (a pool closing), so it's started again each time.
(while true; do in_namespace port-forward service/idleo-database-rw 15432:5432 >>"$out/smoke.log" 2>&1; sleep 0.2; done) &
forward_pid=$!
deadline=$((SECONDS + 30))
until nc -z localhost 15432 2>/dev/null; do
  ((SECONDS < deadline)) || fail "the port-forward to the database didn't open"
  sleep 1
done
(
  . "$root/scripts/lib/toolchains.sh"
  cd control-plane && ./mvnw -B -q test -Dtest=ApplicationRoleTests -Dsurefire.failIfNoSpecifiedTests=false \
    -Droles.url="jdbc:postgresql://localhost:15432/controlplane?sslmode=disable" -Droles.migration-password="$migration_password" \
    -Droles.application-password="$application_password"
) >"$out/smoke-roles.log" 2>&1 || fail "ApplicationRoleTests failed against the cluster's database; see private/k8s/logs/smoke-roles.log"
pkill -P "$forward_pid" 2>/dev/null || true
kill "$forward_pid" 2>/dev/null || true
pass "the database is $primary and a standby, streaming; ApplicationRoleTests passes against its users"

# 4. The client's address: a request writing its own X-Forwarded-For is counted under the address the ingress saw.
forged=203.0.113.77
curl -sf -o /dev/null -H "X-Forwarded-For: $forged" "$api/v1/public/availability" ||
  fail "GET /v1/public/availability failed"
counters="$(in_namespace exec idleo-valkey-0 -c valkey -- valkey-cli --user idleo --scan --pattern 'limits:requests:ip:*')"
! grep -q "$forged" <<<"$counters" || fail "the made-up address $forged was counted: $counters"
counted="$(sed -n 's/^limits:requests:ip:\(.*\):[0-9]*$/\1/p' <<<"$counters" | grep -v '^10\.42\.' | head -1)"
[[ -n "$counted" ]] || fail "no request was counted under the client's own address (only the ingress's?): $counters"
pass "a made-up X-Forwarded-For ($forged) is ignored; the request counts under $counted, the address the ingress saw"

# 5. A fake-GPU agent on this machine, enrolled and connected over mutual TLS to localhost:$agents_port, which is the
#    control plane's own load balancer, not the ingress.
user="smoke-$(openssl rand -hex 4)"
token="$(stack_token "$user")"
[[ -n "$token" ]] || fail "no token from the development sign-in server"
api_get() { curl -sf "$api$1" -H "Authorization: Bearer $token"; }
api_put() { curl -sf -X PUT "$api$1" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d "$2"; }
user_id="$(api_get /v1/me | json_get .id)"
log "building the agent"
stack_build_agent
host_id="$(stack_register_host "$token" "Smoke test laptop" "$user-host")"
rm -rf "$state/agent-1"
stack_agent_config 1
stack_enrol_agent 1 "$(stack_enrolment_code "$token" "$host_id")"
: >"$out/agent-1.log"
stack_run_agent 1 --fake-gpu-model "RTX 4060 Laptop" --fake-gpu-vram-mb 8192 --fake-free-disk-mb 200000
agent_pid="$(cat "$state/agent-1.pid")"
host_online() { [[ "$(api_get "/v1/hosts/$host_id" | json_get .online)" == True ]]; }
deadline=$((SECONDS + 60))
until host_online; do
  kill -0 "$agent_pid" 2>/dev/null || fail "the agent exited; see private/k8s/logs/agent-1.log"
  ((SECONDS < deadline)) || fail "the host didn't come online within 60 s; see private/k8s/logs/agent-1.log"
  sleep 1
done
issuer="$(openssl x509 -in "$state/agent-1/host.crt" -noout -issuer 2>/dev/null | sed 's/^issuer= *//')"
pass "the agent enrolled (its certificate from $issuer) and connected over mutual TLS to localhost:$agents_port; online"

# 6. A booking, run by the agent's development runner to the end.
api_put "/v1/hosts/$host_id/listing" \
  '{"batchPricePaisePerMin": 2, "gpuTempCapC": 85, "maxConcurrentSessions": 1, "keepFreeGb": 10}' >/dev/null ||
  fail "the host's listing couldn't be set"
ops activate-host --host "$host_id" --reason "'Cluster smoke test'" || fail "activate-host failed; see private/k8s/logs/smoke.log"
ops grant-credits --user "$user_id" --paise 10000 --reason "'Cluster smoke test'" --key "$user" ||
  fail "grant-credits failed; see private/k8s/logs/smoke.log"
session="$(curl -sf -X POST "$api/v1/sessions" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: \"$user-session\"" \
  -d '{"templateId": "cuda-smoke-test", "maxPricePaisePerMin": 5, "maxSpendPaise": 1000}' | json_get .id)" ||
  fail "POST /v1/sessions failed"
booked=$SECONDS
deadline=$((SECONDS + 240))
until [[ "$(api_get "/v1/sessions/$session" | json_get .state)" == COMPLETED ]]; do
  ((SECONDS < deadline)) || fail "session $session didn't complete within 240 s (it's $(api_get "/v1/sessions/$session" | json_get .state))"
  sleep 1
done
pass "a booking (cuda-smoke-test, the development runner) ran to COMPLETED in $((SECONDS - booked)) s"

# 7. A rolling restart: the new pod is ready first; the old one tells its agents to reconnect as it stops, and the
#    agent is back, through the load balancer, on the new one. What an agent loses is the time from being told to
#    being connected again, by its own log; meanwhile the API answers every request (readiness, which has no limit).
control_plane=deployment/idleo-control-plane
in_namespace get deployment idleo-control-plane-primary >/dev/null 2>&1 && control_plane=deployment/idleo-control-plane-primary
old_pod="$(in_namespace get pods -l idleo.in/control-plane=true --field-selector=status.phase=Running \
  -o jsonpath='{.items[0].metadata.name}')"
agent_lines="$(wc -l <"$out/agent-1.log")"
in_namespace rollout restart "$control_plane" >/dev/null
restarted=$SECONDS requests=0 failed=0
while in_namespace get pod "$old_pod" >/dev/null 2>&1 ||
  ! in_namespace rollout status "$control_plane" --timeout=1s >/dev/null 2>&1; do
  requests=$((requests + 1))
  curl -sf -o /dev/null --max-time 3 "$api/actuator/health/readiness" || failed=$((failed + 1))
  ((SECONDS - restarted < 300)) || fail "the rolling restart didn't finish within 300 s"
  sleep 0.2
done
took=$((SECONDS - restarted))
((failed == 0)) || fail "$failed of $requests requests to the API failed during the rolling restart"
gap_ms="$(tail -n +"$((agent_lines + 1))" "$out/agent-1.log" | python3 -c '
import re, sys
from datetime import datetime
told = back = None
for line in sys.stdin:
    at = datetime.fromisoformat(line.split()[0].replace("Z", "+00:00"))
    if told is None and "This control plane is stopping; reconnect" in line:
        told = at
    elif told is not None and " connected host_id=" in line:
        back = at
        break
print(int((back - told).total_seconds() * 1000) if told and back else "")')"
[[ -n "$gap_ms" ]] || fail "the agent wasn't told to reconnect, or didn't connect again; see private/k8s/logs/agent-1.log"
kill -0 "$agent_pid" 2>/dev/null || fail "the agent exited during the rolling restart"
host_online || fail "the host isn't online after the rolling restart"
# Its backoff's first delay is at most a second (agent/src/backoff.rs), and a new stream takes a moment more.
((gap_ms <= 3000)) || fail "the agent took $gap_ms ms to reconnect after the rolling restart"
pass "a rolling restart took $took s with all $requests API requests answered; the agent was told to reconnect and was back in $gap_ms ms"

echo
printf '  %s\n' "${summary[@]}"
