#!/usr/bin/env bash
# The compatibility test (ADR 0013): the real Rust agent against the real control plane.
#
# Starts PostgreSQL, Valkey and the development sign-in server (compose.yaml) with the control plane
# on the local profile, signs in, registers a host, enrols the agent with a fake GPU, and watches
# GET /v1/hosts/{id} report it online. Then it books sessions (ADRs 0015, 0016): free credit, a
# listing and activation; a session the agent runs with its development runner, checkpointing to
# object storage (MinIO), that completes and whose output the renter downloads; and a session whose
# agent is killed mid-run, whose lease lapses, and which resumes as attempt 2 from its last complete
# checkpoint when the agent comes back. Finally it watches the host go offline once the agent stops. Everything it writes
# (dev CA, agent config and keys, logs) stays under the gitignored private/. Everything it starts is
# stopped on exit, even on failure, and the script checks that nothing is left.
#
#   scripts/e2e/agent-online.sh                 run the check
#   scripts/e2e/agent-online.sh --keep-running  start only the stack, print a curl with a fresh
#                                               token, and keep it up until Ctrl-C
#
# Ports, from the environment: E2E_HTTP_PORT (8080), E2E_GRPC_PORT (9443), LOCAL_SIGN_IN_PORT (8099),
# LOCAL_STORAGE_PORT (9100).
set -euo pipefail

cd "$(dirname "$0")/../.."
root="$(pwd)"

keep_running=false
case "${1:-}" in
  "") ;;
  --keep-running) keep_running=true ;;
  *) echo "usage: $0 [--keep-running]" >&2; exit 2 ;;
esac

http_port="${E2E_HTTP_PORT:-8080}"
grpc_port="${E2E_GRPC_PORT:-9443}"
sign_in_port="${LOCAL_SIGN_IN_PORT:-8099}"
storage_port="${LOCAL_STORAGE_PORT:-9100}"
startup_timeout="${E2E_STARTUP_TIMEOUT:-600}"
# A short lease, so losing the host shows within seconds (production: 2 minutes, renewed every 30 s).
lease_ttl="${E2E_LEASE_TTL:-10s}"
out="$root/private/e2e"
api="http://localhost:$http_port"
sign_in="http://localhost:$sign_in_port/idleo-local"
compose_project=idleo-e2e

# Rust may be installed by rustup without being on PATH (Homebrew's rustup on a Mac).
for dir in "$HOME/.cargo/bin" /opt/homebrew/opt/rustup/bin; do
  [[ -d "$dir" ]] && PATH="$PATH:$dir"
done

if ! git check-ignore -q "$out/probe"; then
  echo "e2e: refusing to write keys and tokens: private/e2e/ is not gitignored." >&2
  exit 1
fi

now_ms() { perl -MTime::HiRes=time -e 'printf "%d\n", time * 1000'; }
seconds_since() { perl -e 'printf "%.2f", ($ARGV[1] - $ARGV[0]) / 1000' "$1" "$(now_ms)"; }
log() { printf 'e2e: %s\n' "$*"; }
fail() { printf 'e2e: FAIL: %s\n' "$*" >&2; exit 1; }
# The string value of a JSON field that appears once, whatever the spacing or line breaks.
json_field() { tr -d '\n' | sed -n "s/^[^{]*{.*\"$1\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p"; }
port_in_use() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
# A value from JSON on stdin, by a path like .wallet.reservedPaise.
json_get() { python3 -c 'import json, sys
value = json.load(sys.stdin)
for key in sys.argv[1].strip(".").split("."):
    value = value[int(key)] if isinstance(value, list) else value[key]
print(value)' "$1"; }
api_get() { curl -sf "$api$1" -H "Authorization: Bearer $token"; }
api_post() { curl -sf -X POST "$api$1" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' "${@:2}"; }
# An operator command (docs/runbooks/granting-credits.md, hosts.md) against the running stack.
ops() {
  (
    cd control-plane
    LOCAL_SIGN_IN_PORT="$sign_in_port" COMPOSE_PROJECT_NAME="$compose_project" OPERATOR=e2e-script \
      ./mvnw -B -q spring-boot:run -Dspring-boot.run.profiles=local,ops -Dspring-boot.run.arguments="$*"
  ) >>"$out/ops.log" 2>&1
}
await_state() {
  local session=$1 wanted=$2 limit=$3 state=""
  local deadline=$((SECONDS + limit))
  while ((SECONDS < deadline)); do
    state="$(api_get "/v1/sessions/$session" | json_get .state)"
    [[ "$state" == "$wanted" ]] && return 0
    sleep 0.2
  done
  fail "session $session didn't reach $wanted within $limit s (it's $state)"
}
book() {
  api_post /v1/sessions -H "Idempotency-Key: \"$user-$1\"" \
    -d '{"templateId": "cuda-smoke-test", "maxPricePaisePerMin": 5, "maxSpendPaise": 10000}' | json_get .id
}
# A read-only query against the stack's PostgreSQL.
sql() {
  COMPOSE_PROJECT_NAME="$compose_project" docker compose -f control-plane/compose.yaml exec -T postgres \
    psql -U controlplane -d controlplane -tA -c "$1"
}
# Seconds from a session's first $2 event to its first $3 event after it.
event_seconds() {
  api_get "/v1/sessions/$1" | python3 -c 'import json, sys
from datetime import datetime
at = lambda e: datetime.fromisoformat(e["at"].replace("Z", "+00:00"))
events = json.load(sys.stdin)["events"]
start = at(next(e for e in events if e["state"] == sys.argv[1]))
end = at(next(e for e in events if e["state"] == sys.argv[2] and at(e) >= start))
print("%.3f" % (end - start).total_seconds())' "$2" "$3"
}
# Milliseconds since the epoch of a session's first $2 event.
event_ms() {
  api_get "/v1/sessions/$1" | python3 -c 'import json, sys
from datetime import datetime
e = next(e for e in json.load(sys.stdin)["events"] if e["state"] == sys.argv[1])
print(int(datetime.fromisoformat(e["at"].replace("Z", "+00:00")).timestamp() * 1000))' "$2"
}
# Waits until a session has had an event in state $2 (it may have moved on since).
await_events() {
  local session=$1 wanted=$2 limit=$3
  local deadline=$((SECONDS + limit))
  until api_get "/v1/sessions/$session" | grep -q "\"state\":\"$wanted\""; do
    ((SECONDS < deadline)) || fail "session $session never had a $wanted event within $limit s"
    sleep 0.2
  done
}
# Downloads a completed session's result.json through GET /outputs and its pre-signed link.
download_output() {
  local outputs url
  outputs="$(api_get "/v1/sessions/$1/outputs")" || fail "GET /outputs failed for $1"
  url="$(printf '%s' "$outputs" | python3 -c 'import json, sys
print(next(f["url"] for f in json.load(sys.stdin)["files"] if f["path"] == "result.json"))')"
  curl -sf "$url" -o "$2" || fail "downloading $1's output failed"
}
# The agent with a fake GPU and the development runner (ADR 0016), in the background.
run_agent() {
  IDLEO_LOG=info "$agent" --config "$out/agent.toml" run --fake-gpu --dev-runner >>"$out/agent.log" 2>&1 &
  agent_pid=$!
}

control_plane_pid=""
agent_pid=""
summary=()

cleanup() {
  local status=$?
  set +e
  if [[ -n "$agent_pid" ]] && kill -0 "$agent_pid" 2>/dev/null; then
    kill -TERM "$agent_pid" 2>/dev/null
    wait "$agent_pid" 2>/dev/null
  fi
  if [[ -n "$control_plane_pid" ]] && kill -0 "$control_plane_pid" 2>/dev/null; then
    log "stopping the control plane"
    kill -TERM -- "-$control_plane_pid" 2>/dev/null
    for _ in $(seq 1 60); do
      kill -0 "$control_plane_pid" 2>/dev/null || break
      sleep 0.5
    done
    kill -KILL -- "-$control_plane_pid" 2>/dev/null
    wait "$control_plane_pid" 2>/dev/null
  fi
  log "removing the compose services"
  COMPOSE_PROJECT_NAME="$compose_project" docker compose -f control-plane/compose.yaml down --volumes --remove-orphans \
    >>"$out/compose.log" 2>&1

  # Nothing this script started may still be running.
  local leftovers=""
  if [[ -n "$(docker ps -q --filter "label=com.docker.compose.project=$compose_project")" ]]; then
    leftovers+=" containers"
  fi
  if [[ -n "$control_plane_pid" ]] && pgrep -g "$control_plane_pid" >/dev/null 2>&1; then
    leftovers+=" control-plane"
  fi
  if pgrep -f "$out/agent.toml" >/dev/null 2>&1; then
    leftovers+=" agent"
  fi
  if [[ -n "$leftovers" ]]; then
    printf 'e2e: FAIL: still running after teardown:%s\n' "$leftovers" >&2
    status=1
  else
    summary+=("teardown: nothing left running (containers, control plane, agent): PASS")
  fi

  if [[ ${#summary[@]} -gt 0 ]]; then
    printf '\ne2e summary\n'
    printf '  %s\n' "${summary[@]}"
  fi
  if [[ $status -eq 0 ]]; then
    printf '  result: PASS\n'
  else
    printf '  result: FAIL (logs in private/e2e/)\n'
  fi
  exit "$status"
}

mkdir -p "$out"
rm -f "$out"/*.log
trap cleanup EXIT
on_signal() {
  # Ctrl-C is how --keep-running is meant to end.
  if $keep_running; then exit 0; else exit 130; fi
}
trap on_signal INT TERM

for port in "$http_port" "$grpc_port" "$sign_in_port" "$storage_port"; do
  if port_in_use "$port"; then
    fail "port $port is already in use; stop what holds it, or move the stack with E2E_HTTP_PORT, E2E_GRPC_PORT, LOCAL_SIGN_IN_PORT or LOCAL_STORAGE_PORT"
  fi
done
docker info >/dev/null 2>&1 || fail "Docker isn't running"

# 1. The development CA, with the localhost server certificate for the gRPC port.
control-plane/scripts/dev-ca.sh

# 2. The control plane on the local profile, which starts compose.yaml. Its own process group, so
#    teardown reaches Maven's forked JVM too. .env is deliberately not read: the run must not
#    depend on a developer's local overrides.
log "starting the control plane (logs: private/e2e/control-plane.log)"
set -m
(
  cd control-plane
  unset IDENTITY_ISSUERS_0_ISSUERURI IDENTITY_ISSUERS_0_AUDIENCE SPRING_PROFILES_ACTIVE SPRING_PROFILES_INCLUDE
  export SERVER_PORT="$http_port" SPRING_GRPC_SERVER_PORT="$grpc_port" LOCAL_SIGN_IN_PORT="$sign_in_port"
  export LOCAL_STORAGE_PORT="$storage_port" COMPOSE_PROJECT_NAME="$compose_project"
  # The smoke test as the development runner runs it: 20 s, a checkpoint every 5 s (ADR 0016).
  export SESSIONS_TEMPLATES_CATALOG="file:$root/templates/development.yaml"
  # A short lease, renewed every 3 s; a lost host counts as lost 2 s after it ends.
  export SESSIONS_LEASES_TTL="$lease_ttl" SESSIONS_LEASES_RENEW_EVERY=3s
  export SESSIONS_LEASES_TRANSIT_ALLOWANCE=2s SESSIONS_LEASES_LAPSE_SWEEP_EVERY=1s
  exec ./mvnw -B -q spring-boot:run -Dspring-boot.run.profiles=local
) >"$out/control-plane.log" 2>&1 &
control_plane_pid=$!
set +m

started_at="$(now_ms)"
deadline=$((SECONDS + startup_timeout))
until curl -sf "$api/actuator/health/readiness" 2>/dev/null | grep -q '"status":"UP"'; do
  kill -0 "$control_plane_pid" 2>/dev/null || fail "the control plane exited during startup; see private/e2e/control-plane.log"
  ((SECONDS < deadline)) || fail "the control plane wasn't ready within ${startup_timeout} s"
  sleep 1
done
summary+=("control plane ready: $(seconds_since "$started_at") s")

# 3. A token from the development sign-in server, for an invented user. Compose only waits for its
#    port, so wait for its discovery document too.
deadline=$((SECONDS + 60))
until curl -sf "$sign_in/.well-known/openid-configuration" >/dev/null 2>&1; do
  ((SECONDS < deadline)) || fail "the development sign-in server didn't answer within 60 s"
  sleep 0.5
done
user="e2e-$(perl -e 'printf "%08x", int(rand(2**32))')"
token="$(curl -sf -X POST "$sign_in/token" \
  -d grant_type=client_credentials -d "client_id=$user" -d client_secret=unused -d scope=openid |
  json_field access_token)"
[[ -n "$token" ]] || fail "no token from the development sign-in server"

if $keep_running; then
  printf '\nThe stack is up. Signed in as %s@example.com; the token lasts an hour.\n\n' "$user"
  printf "  curl -s -H 'Authorization: Bearer %s' %s/v1/me\n\n" "$token" "$api"
  printf 'A new token:\n\n'
  printf "  curl -s -X POST %s/token -d grant_type=client_credentials -d client_id=%s -d client_secret=unused -d scope=openid\n\n" "$sign_in" "$user"
  printf 'Control plane logs: private/e2e/control-plane.log. Ctrl-C stops everything.\n'
  wait "$control_plane_pid" || true
  exit 0
fi

# 4. Register a host and get its enrolment code. The code is never printed or logged.
host_json="$(curl -sf -X POST "$api/v1/hosts" -H "Authorization: Bearer $token" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: \"$user-host\"" -d '{"name":"End-to-end test host"}')" ||
  fail "POST /v1/hosts failed"
host_id="$(printf '%s' "$host_json" | json_field id)"
[[ -n "$host_id" ]] || fail "POST /v1/hosts returned no id"
code="$(curl -sf -X POST "$api/v1/hosts/$host_id/enrolment-code" -H "Authorization: Bearer $token" | json_field code)"
[[ -n "$code" ]] || fail "no enrolment code"
summary+=("host registered: $host_id")

# 5. Build and enrol the agent.
log "building the agent"
# From inside agent/, so rustup uses the toolchain pinned in agent/rust-toolchain.toml; it looks in
# the current directory, not at --manifest-path.
(cd agent && cargo build --release --locked --quiet) >"$out/agent-build.log" 2>&1 ||
  fail "cargo build failed; see private/e2e/agent-build.log"
agent="$root/agent/target/release/idleo-agent"
rm -rf "$out/agent"
cat >"$out/agent.toml" <<TOML
ca_root = "$root/private/dev-ca/root.pem"
data_dir = "$out/agent"
log_format = "text"

[control_plane]
host = "localhost"
port = $grpc_port
TOML
"$agent" --config "$out/agent.toml" enrol --code "$code" >>"$out/agent.log" 2>&1 ||
  fail "enrolment failed; see private/e2e/agent.log"
unset code
summary+=("agent enrolled")

host_online() {
  curl -sf "$api/v1/hosts/$host_id" -H "Authorization: Bearer $token" | grep -q "\"online\":$1"
}

# 6. Run the agent and wait for the host to come online.
agent_started="$(now_ms)"
run_agent
deadline=$((SECONDS + 30))
until host_online true; do
  kill -0 "$agent_pid" 2>/dev/null || fail "the agent exited; see private/e2e/agent.log"
  ((SECONDS < deadline)) || fail "the host didn't come online within 30 s"
  sleep 0.5
done
to_online="$(seconds_since "$agent_started")"
summary+=("agent start to online: $to_online s: PASS")
host_view="$(curl -sf "$api/v1/hosts/$host_id" -H "Authorization: Bearer $token")"
printf '%s' "$host_view" | grep -q '"gpus":\[{"model":"IdleO Fake GPU","vramMb":12282,"gone":false}\]' ||
  fail "the host's GPUs aren't listed: $host_view"
summary+=("fake GPU listed on the host: PASS")

# 7. Book a session (ADR 0015): the user gets ₹500 of free credit, the host lists at 2 paise a minute
#    and is activated, the agent accepts the offer, and 30 minutes (60 paise) is held.
user_id="$(api_get /v1/me | json_get .id)"
ops grant-credits --user "$user_id" --paise 50000 --reason "'End-to-end test credit'" --key "e2e-$user_id" ||
  fail "grant-credits failed; see private/e2e/ops.log"
curl -sf -X PUT "$api/v1/hosts/$host_id/listing" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  -d '{"batchPricePaisePerMin": 2, "gpuTempCapC": 85, "maxConcurrentSessions": 1}' >/dev/null || fail "PUT listing failed"
ops activate-host --host "$host_id" --reason "'End-to-end test host'" || fail "activate-host failed; see private/e2e/ops.log"
wallet_before="$(api_get /v1/wallet)"
[[ "$(printf '%s' "$wallet_before" | json_get .availablePaise)" == 50000 ]] || fail "the wallet isn't ₹500: $wallet_before"

# 8. Book, run, complete (ADR 0016): the agent starts the session with scoped storage grants and a
#    lease, runs it with the development runner (20 s, a checkpoint every 5 s), and the control plane
#    sees its outputs' completion marker. The renter downloads the output; the hold is released and,
#    with no metering yet, nothing is charged.
posted="$(now_ms)"
session_id="$(book first)"
await_state "$session_id" RUNNING 60
summary+=("run: POST to MATCHED $(event_seconds "$session_id" REQUESTED MATCHED) s, to PROVISIONING $(event_seconds "$session_id" REQUESTED PROVISIONING) s, to RUNNING $(event_seconds "$session_id" REQUESTED RUNNING) s")
wallet_held="$(api_get /v1/wallet)"
[[ "$(printf '%s' "$wallet_held" | json_get .reservedPaise)" == 60 ]] || fail "expected 60 paise held: $wallet_held"
summary+=("wallet before booking: $wallet_before")
summary+=("wallet while running:  $wallet_held: PASS")
await_state "$session_id" COMPLETED 90
summary+=("run: POST to COMPLETED $(event_seconds "$session_id" REQUESTED COMPLETED) s (wall clock $(seconds_since "$posted") s): PASS")
download_output "$session_id" "$out/output-normal.json"
python3 - "$out/output-normal.json" <<'PY' || fail "the output isn't attempt 1 without a resume: $(cat "$out/output-normal.json")"
import json, sys
output = json.load(open(sys.argv[1]))
assert output["attempt"] == 1 and output["resumed_from"] is None and output["checkpoints_written"] == 3, output
PY
summary+=("downloaded output (normal run): $(tr -d '\n ' <"$out/output-normal.json"): PASS")
wallet_after="$(api_get /v1/wallet)"
[[ "$(printf '%s' "$wallet_after" | json_get .availablePaise)" == 50000 &&
  "$(printf '%s' "$wallet_after" | json_get .reservedPaise)" == 0 ]] || fail "the hold wasn't released: $wallet_after"
summary+=("wallet after COMPLETED: $wallet_after: PASS")
checkpoints="$(sql "select count(*) from sessions.checkpoints where session_id = '$session_id'")"
[[ "$checkpoints" == 3 ]] || fail "expected 3 checkpoints recorded, found $checkpoints"
summary+=("checkpoints recorded once their markers were confirmed in storage: $checkpoints: PASS")

# 9. Lose the host mid-run: book again, wait for RUNNING and two checkpoints, kill -9 the agent. The
#    lease lapses, the session goes HOST_LOST and back to REQUESTED as attempt 2 and waits for a
#    host; when the agent comes back it's matched again, resumes from the last complete checkpoint
#    and completes.
lost_id="$(book second)"
await_state "$lost_id" RUNNING 60
deadline=$((SECONDS + 30))
until (($(sql "select count(*) from sessions.checkpoints where session_id = '$lost_id'") >= 2)); do
  ((SECONDS < deadline)) || fail "session $lost_id didn't record two checkpoints within 30 s"
  sleep 0.2
done
killed_at="$(now_ms)"
kill -KILL "$agent_pid"
wait "$agent_pid" 2>/dev/null || true
agent_pid=""
await_events "$lost_id" HOST_LOST 60
lost_at="$(event_ms "$lost_id" HOST_LOST)"
summary+=("kill -9 to HOST_LOST: $(perl -e 'printf "%.2f", ($ARGV[1] - $ARGV[0]) / 1000' "$killed_at" "$lost_at") s (lease $lease_ttl, plus a 2 s transit allowance): PASS")
[[ "$(api_get "/v1/sessions/$lost_id" | json_get .attempt)" == 2 ]] || fail "the lost session isn't on attempt 2"
restarted="$(now_ms)"
run_agent
await_state "$lost_id" COMPLETED 120
summary+=("agent restart to COMPLETED: $(seconds_since "$restarted") s: PASS")
download_output "$lost_id" "$out/output-resumed.json"
resumed_seq="$(sql "select max(step) from sessions.checkpoints where session_id = '$lost_id' and attempt = 1")"
python3 - "$out/output-resumed.json" "$lost_id" "$resumed_seq" <<'PY' ||
import json, sys
output, session, seq = json.load(open(sys.argv[1])), sys.argv[2], int(sys.argv[3])
assert output["attempt"] == 2, output
assert output["resumed_from"] == {"prefix": f"sessions/{session}/attempt-1/checkpoints/", "seq": seq}, output
PY
  fail "the output isn't attempt 2 resumed from checkpoint $resumed_seq: $(cat "$out/output-resumed.json")"
summary+=("downloaded output (resumed run): $(tr -d '\n ' <"$out/output-resumed.json"): PASS")
# Fencing: attempt 2 ran only after attempt 1 was declared lost, which happens only once its lease and
# the transit allowance have passed.
fenced="$(sql "select (select running_at from sessions.attempts where session_id = '$lost_id' and attempt = 2)
  > (select at from sessions.session_events where session_id = '$lost_id' and to_state = 'HOST_LOST')")"
[[ "$fenced" == t ]] || fail "attempt 2 ran before attempt 1 was declared lost"
summary+=("fencing: attempt 2 ran only after attempt 1's lease lapsed: PASS")
[[ "$(api_get /v1/wallet | json_get .reservedPaise)" == 0 ]] || fail "the resumed session's hold wasn't released"
api_get "/v1/sessions/$lost_id" | python3 -c 'import json, sys
for e in json.load(sys.stdin)["events"]:
    print("    session_event: %-12s %-24s %s" % (e["state"], e["reason"], e["at"]))' >"$out/session-events.txt"
while IFS= read -r line; do summary+=("${line#    }"); done <"$out/session-events.txt"
grep -q "resumed from a checkpoint" "$out/agent.log" || fail "the agent never logged resuming from a checkpoint"
for field in secret_access_key session_token X-Amz-Security-Token X-Amz-Credential; do
  if grep -q "$field" "$out/agent.log" "$out/control-plane.log"; then
    fail "a storage credential field ($field) appears in the logs"
  fi
done
summary+=("no storage credentials in the agent or control-plane logs: PASS")

# 10. Stop the agent and wait for the host to go offline.
stopped_at="$(now_ms)"
kill -TERM "$agent_pid"
agent_status=0
wait "$agent_pid" || agent_status=$?
agent_pid=""
[[ $agent_status -eq 0 ]] || fail "the agent exited with $agent_status after SIGTERM"
deadline=$((SECONDS + 30))
until host_online false; do
  ((SECONDS < deadline)) || fail "the host didn't go offline within 30 s"
  sleep 0.5
done
to_offline="$(seconds_since "$stopped_at")"
# Presence lives 15 s after the last heartbeat; a clean close deletes it at once.
if perl -e 'exit($ARGV[0] < 5 ? 0 : 1)' "$to_offline"; then
  how="the clean close"
else
  how="the 15 s presence expiry, so the clean close didn't remove presence"
fi
summary+=("SIGTERM to offline: $to_offline s, from $how: PASS")
