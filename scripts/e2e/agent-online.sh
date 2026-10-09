#!/usr/bin/env bash
# The compatibility test (ADR 0013): the real Rust agent against the real control plane.
#
# Starts PostgreSQL, Valkey and the development sign-in server (compose.yaml) with the control plane
# on the local profile, signs in, registers a host, enrols the agent with a fake GPU, and watches
# GET /v1/hosts/{id} report it online. Then it enrols three more agents, so four sessions run at once,
# one per host, and charges them (ADRs 0015, 0016, 0017): a two-and-a-half-minute run charged exactly
# three minutes; a run the renter stops mid-way after a last checkpoint; a run whose agent is killed,
# whose lease lapses, and which resumes as attempt 2, the minutes lost after attempt 1's checkpoint
# given back; and a run stopped at its spending cap. A fifth agent serves the web app's journeys
# (ADR 0018): in a real browser, Home follows a booked session to its charge; a renter books on a laptop
# (form, upload, quote) and watches the session live to its output; and stops one on a phone. A
# sixth serves the booking contract (ADR 0019): a 12 MiB file uploaded in parts straight to storage,
# a quote, a session with parameters and that file as its input, followed on its event stream to
# settled, its logs, and an output proving the job saw the exact file and parameters, then three errors
# by their types. A seventh fine-tunes on the simulated fine-tune service with a small JSONL and
# downloads its adapter. It checks storage's CORS for the web app, runs the wallet check, then watches
# the hosts go offline once the agents stop.
# Everything it writes
# (dev CA, agent config and keys, logs) stays under the gitignored private/. Everything it starts is
# stopped on exit, even on failure, and the script checks that nothing is left.
#
#   scripts/e2e/agent-online.sh                 run the check
#   scripts/e2e/agent-online.sh --keep-running  the stack to look at and try by hand, until Ctrl-C:
#                                               two laptops' fake GPUs (RTX 4060 Laptop, 8 GB, at
#                                               ₹7.20/h; RTX 4080 Laptop, 12 GB, at ₹10.80/h) with the
#                                               development runner, the web app on :3000 (not with
#                                               E2E_SERVE_WEB=0, for pnpm dev), and E2E_ACCOUNT (nikki)
#                                               with ₹500 of credit; prints a curl with a fresh token
#   scripts/e2e/agent-online.sh --web           the web app's checks (.github/workflows/web.yml): the
#                                               stack and one agent, then every screen and state with
#                                               axe and visual baselines, the Firebase screens, the
#                                               journey, Lighthouse's budgets and the size budget
#
# Ports, from the environment: E2E_HTTP_PORT (8080), E2E_GRPC_PORT (9443), LOCAL_SIGN_IN_PORT (8099),
# LOCAL_STORAGE_PORT (9100), E2E_WEB_PORT (3000), E2E_FIREBASE_WEB_PORT (3001).
# --web compares screenshots with VISUAL_BASELINE_DIR (web/visual-baseline): an empty one is filled
# first; otherwise every picture must match, and a new or changed picture fails until accepted with
# E2E_UPDATE_SNAPSHOTS=all (Playwright's --update-snapshots; web/README.md "Screenshots").
set -euo pipefail

cd "$(dirname "$0")/../.."
root="$(pwd)"

keep_running=false
web_only=false
case "${1:-}" in
  "") ;;
  --keep-running) keep_running=true ;;
  --web) web_only=true ;;
  *) echo "usage: $0 [--keep-running | --web]" >&2; exit 2 ;;
esac

http_port="${E2E_HTTP_PORT:-8080}"
grpc_port="${E2E_GRPC_PORT:-9443}"
sign_in_port="${LOCAL_SIGN_IN_PORT:-8099}"
storage_port="${LOCAL_STORAGE_PORT:-9100}"
web_port="${E2E_WEB_PORT:-3000}"
firebase_web_port="${E2E_FIREBASE_WEB_PORT:-3001}"
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
# The web app pins Node 24 (web/.node-version); a Mac may keep it beside a newer Node.
for dir in /opt/homebrew/opt/node@24/bin "$HOME/.local/node24-bin"; do
  [[ -d "$dir" ]] && PATH="$dir:$PATH"
done
export COREPACK_ENABLE_DOWNLOAD_PROMPT=0

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
json_str() { python3 -c 'import json, sys; print(json.dumps(sys.argv[1]))' "$1"; }
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
# Books template $2 with a spending cap of $3 paise, as whoever $token belongs to.
book() {
  api_post /v1/sessions -H "Idempotency-Key: \"$user-$1\"" \
    -d "{\"templateId\": \"$2\", \"maxPricePaisePerMin\": 5, \"maxSpendPaise\": $3}" | json_get .id
}
# A read-only query against the stack's PostgreSQL.
sql() {
  COMPOSE_PROJECT_NAME="$compose_project" docker compose -f control-plane/compose.yaml exec -T postgres \
    psql -U controlplane -d controlplane -tA -c "$1"
}
# A field of a host's presence record in the stack's Valkey (ADR 0011): $1 the host, $2 the field.
presence() {
  COMPOSE_PROJECT_NAME="$compose_project" docker compose -f control-plane/compose.yaml exec -T valkey \
    valkey-cli HGET "agentgateway:presence:$1" "$2"
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
# Uploads local files as one upload (ADR 0019, ADR 0022): $1 its name, $2 its idempotency key, then pairs of a path
# in the upload and a local file. Each file's part links are asked for per file, its parts PUT straight to MinIO,
# and it's completed with their ETags; the upload is ready once every file is. Prints the upload's id.
upload_folder() {
  local name=$1 key=$2 files_json="[" separator="" upload_json id part_size i n parts links url etag etags
  shift 2
  local paths=() locals=()
  while (($#)); do
    paths+=("$1")
    locals+=("$2")
    shift 2
  done
  for i in "${!paths[@]}"; do
    files_json+="$separator{\"path\": $(json_str "${paths[$i]}"), \"sizeBytes\": $(wc -c <"${locals[$i]}" | tr -d ' ')}"
    separator=","
  done
  files_json+="]"
  upload_json="$(api_post /v1/uploads -H "Idempotency-Key: \"$user-$key\"" \
    -d "{\"name\": $(json_str "$name"), \"files\": $files_json}")" || fail "POST /v1/uploads failed for $name"
  printf '%s' "$upload_json" >"$out/upload-$key.json"
  id="$(printf '%s' "$upload_json" | json_get .upload.id)"
  part_size="$(printf '%s' "$upload_json" | json_get .upload.partSizeBytes)"
  for i in "${!paths[@]}"; do
    parts="$(printf '%s' "$upload_json" | python3 -c 'import json, sys
print(next(f["partCount"] for f in json.load(sys.stdin)["files"] if f["path"] == sys.argv[1]))' "${paths[$i]}")"
    etags="["
    separator=""
    if ((parts > 0)); then
      links="$(api_post "/v1/uploads/$id/part-urls" -d "{\"path\": $(json_str "${paths[$i]}"),
        \"partNumbers\": $(python3 -c 'import sys; print(list(range(1, int(sys.argv[1]) + 1)))' "$parts")}")" ||
        fail "POST /part-urls failed for ${paths[$i]}"
      for ((n = 1; n <= parts; n++)); do
        url="$(printf '%s' "$links" | python3 -c 'import json, sys
print(json.load(sys.stdin)["parts"][int(sys.argv[1]) - 1]["url"])' "$n")"
        etag="$(dd if="${locals[$i]}" bs="$part_size" skip=$((n - 1)) count=1 2>/dev/null |
          curl -sf -X PUT --data-binary @- -D - -o /dev/null "$url" | tr -d '\r' | sed -n 's/^[Ee][Tt]ag: *//p')"
        [[ -n "$etag" ]] || fail "part $n of ${paths[$i]} didn't PUT"
        etags+="$separator{\"number\": $n, \"etag\": $(json_str "$etag")}"
        separator=","
      done
    fi
    etags+="]"
    api_post "/v1/uploads/$id/complete" -d "{\"files\": [{\"path\": $(json_str "${paths[$i]}"), \"parts\": $etags}]}" \
      >"$out/upload-$key-complete.json" || fail "completing ${paths[$i]} of $name failed"
  done
  [[ "$(json_get .upload.status <"$out/upload-$key-complete.json")" == ready ]] || fail "the upload $name isn't ready"
  printf '%s\n' "$id"
}
# One local file $1 uploaded as $2, with idempotency key $3; prints the upload's id.
upload_file() { upload_folder "$2" "$3" "$2" "$1"; }
# Agent $1 (1 to $agents) with a fake GPU and the development runner (ADR 0016), in the background.
# Its pid is in a file, since the host-loss scenario kills and restarts one from a subshell. With
# --keep-running, each agent's fake GPU is a laptop's (fake_gpus below).
run_agent() {
  local gpu=()
  if $keep_running; then
    IFS='|' read -r model vram _ <<<"${fake_gpus[$(($1 - 1))]}"
    gpu=(--fake-gpu-model "$model" --fake-gpu-vram-mb "$vram")
  fi
  # ${gpu[@]+…}: an empty array is "unbound" to macOS's bash 3.2 under set -u.
  IDLEO_LOG=info "$agent" --config "$out/agent-$1.toml" run --fake-gpu ${gpu[@]+"${gpu[@]}"} --dev-runner >>"$out/agent-$1.log" 2>&1 &
  echo $! >"$out/agent-$1.pid"
}
agent_running() { [[ -f "$out/agent-$1.pid" ]] && kill -0 "$(cat "$out/agent-$1.pid")" 2>/dev/null; }
# Waits until metering has closed a session: settled, reviewed, and its hold released.
await_closed() {
  local deadline=$((SECONDS + $2))
  until [[ "$(sql "select closed_at is not null from metering.metered_sessions where session_id = '$1'")" == t ]]; do
    ((SECONDS < deadline)) || fail "metering didn't close session $1 within $2 s"
    sleep 0.5
  done
}
# How many of a session's minutes are charged, net of refunds (of attempt $2 only, if given).
billed_minutes() {
  sql "select count(*) from metering.usage_minutes where session_id = '$1' and billed and refund_txn is null${2:+ and agent_attempt = $2}"
}
# The session's ledger entries, one line each; and whether every one of its transactions balances.
print_ledger() {
  sql "select e.txn_id || ' ' || rpad(e.ref_type, 14) || ' ' || rpad(e.account, 16) || ' ' || rpad(e.direction, 6) || ' ' || e.amount_paise
       from billing.ledger_entries e where e.ref_id = '$1' order by e.created_at, e.txn_id, e.account"
}
ledger_balanced() {
  sql "select coalesce(bool_and(debits = credits), false) from (
         select txn_id, sum(amount_paise) filter (where direction = 'debit') as debits,
                sum(amount_paise) filter (where direction = 'credit') as credits
         from billing.ledger_entries where ref_id = '$1' group by txn_id) t"
}
# Whether the session's runner wrote a last checkpoint when asked to stop, and the control plane found it
# complete in storage: its newest checkpoint finished after the stop request (to within a second, for
# skew between storage's clock and the control plane's).
checkpointed_after_stop() {
  grep -q "stopped after a last checkpoint session_id=$1 " "$out"/agent-*.log &&
    [[ "$(sql "select count(*) > 0 from sessions.checkpoints c
       join sessions.attempts a on a.session_id = c.session_id and a.attempt = c.attempt
       where c.session_id = '$1' and a.stop_requested_at is not null
         and c.completed_at >= a.stop_requested_at - interval '1 second'
         and c.step = (select max(step) from sessions.checkpoints where session_id = '$1' and attempt = c.attempt)")" == t ]] &&
    echo t
}
# Scenarios run in subshells, side by side; each writes its summary lines to its own file.
note() { printf '%s\n' "$*" >>"$out/summary-$scenario.txt"; }

# Four charged scenarios, the web journey, the booking contract and the simulated fine-tune, one host each;
# the web checks need one. By hand (--keep-running), two laptops: model|memory in MB|price in paise a minute
# (₹7.20 and ₹10.80 an hour), so booking shows a real price range and a session a real GPU's name.
fake_gpus=("RTX 4060 Laptop|8188|12" "RTX 4080 Laptop|12282|18")
account="${E2E_ACCOUNT:-nikki}"
if $web_only; then agents=1; elif $keep_running; then agents=${#fake_gpus[@]}; else agents=7; fi
control_plane_pid=""
summary=()

cleanup() {
  local status=$?
  set +e
  for pid_file in "$out"/web-*.pid; do
    [[ -f "$pid_file" ]] && kill -TERM "$(cat "$pid_file")" 2>/dev/null
  done
  for pid_file in "$out"/agent-*.pid; do
    [[ -f "$pid_file" ]] && kill -TERM "$(cat "$pid_file")" 2>/dev/null
  done
  for _ in $(seq 1 20); do
    pgrep -f "$out/agent-[0-9]*.toml run" >/dev/null 2>&1 || break
    sleep 0.5
  done
  pkill -KILL -f "$out/agent-[0-9]*.toml run" 2>/dev/null
  for pid_file in "$out"/agent-*.pid; do
    [[ -f "$pid_file" ]] && wait "$(cat "$pid_file")" 2>/dev/null
  done
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
  if pgrep -f "$out/agent-[0-9]*.toml" >/dev/null 2>&1; then
    leftovers+=" agent"
  fi
  if pgrep -f "web/scripts/serve.ts" >/dev/null 2>&1; then
    leftovers+=" web"
  fi
  if [[ -n "$leftovers" ]]; then
    printf 'e2e: FAIL: still running after teardown:%s\n' "$leftovers" >&2
    status=1
  else
    summary+=("teardown: nothing left running (containers, control plane, agents, web): PASS")
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
rm -f "$out"/*.log "$out"/*.pid "$out"/summary-*.txt
trap cleanup EXIT
on_signal() {
  # Ctrl-C is how --keep-running is meant to end.
  if $keep_running; then exit 0; else exit 130; fi
}
trap on_signal INT TERM

for port in "$http_port" "$grpc_port" "$sign_in_port" "$storage_port" "$web_port" "$firebase_web_port"; do
  if port_in_use "$port"; then
    fail "port $port is already in use; stop what holds it, or move the stack with E2E_HTTP_PORT, E2E_GRPC_PORT, LOCAL_SIGN_IN_PORT, LOCAL_STORAGE_PORT, E2E_WEB_PORT or E2E_FIREBASE_WEB_PORT"
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
  # The web app's origin, which may call the API from a browser (ADR 0018).
  export WEB_ALLOWED_ORIGINS="http://localhost:$web_port"
  # The smoke test as the development runner runs it: 20 s, a checkpoint every 5 s (ADR 0016).
  export SESSIONS_TEMPLATES_CATALOG="file:$root/templates/development.yaml"
  # A short lease, renewed every 3 s; a lost host counts as lost 2 s after it ends.
  export SESSIONS_LEASES_TTL="$lease_ttl" SESSIONS_LEASES_RENEW_EVERY=3s
  export SESSIONS_LEASES_TRANSIT_ALLOWANCE=2s SESSIONS_LEASES_LAPSE_SWEEP_EVERY=1s
  # Storage's smallest upload part, so a few MiB really upload in several parts (production: 64 MiB).
  export SESSIONS_UPLOADS_PART_SIZE=5MB
  # Grants that last a minute, so every run outlives its first and the agents swap in fresh ones
  # (MinIO's own credentials still last 15 minutes). Metering ticks every second, and closes a session
  # 20 s after it ends, not 5 minutes, when it's still waiting on a minute only one witness saw.
  export SESSIONS_STORAGE_GRANT_TTL=60s SESSIONS_STORAGE_REFRESH_CHECK_EVERY=1s
  export METERING_TICK_EVERY=1s METERING_REVIEW_AFTER=20s
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

# 3b. Browsers PUT upload parts straight to storage (ADR 0019): storage answers the web app's preflight, and
#     no one else's.
cors="$(curl -si -X OPTIONS "http://localhost:$storage_port/idleo-local/uploads/probe" \
  -H "Origin: http://localhost:$web_port" -H "Access-Control-Request-Method: PUT")"
printf '%s\n' "$cors" >"$out/cors-preflight.txt"
grep -qi "^Access-Control-Allow-Origin: http://localhost:$web_port" <<<"$cors" &&
  grep -qi "^Access-Control-Allow-Methods:.*PUT" <<<"$cors" ||
  fail "storage refused the web app's CORS preflight: $cors"
if curl -si -X OPTIONS "http://localhost:$storage_port/idleo-local/uploads/probe" \
  -H "Origin: http://elsewhere.example" -H "Access-Control-Request-Method: PUT" | grep -qi "^Access-Control-Allow-Origin"; then
  fail "storage allows uploads from another origin"
fi
summary+=("storage CORS: PUT allowed from http://localhost:$web_port, not from elsewhere: PASS")

# 4. Register the hosts and get their enrolment codes. Codes are never printed or logged. By hand, each host
#    is named after its laptop's GPU.
host_name() {
  if $keep_running; then echo "${fake_gpus[$(($1 - 1))]%%|*}"; else echo "End-to-end test host $1"; fi
}
host_ids=()
codes=()
for i in $(seq 1 "$agents"); do
  host_json="$(curl -sf -X POST "$api/v1/hosts" -H "Authorization: Bearer $token" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: \"$user-host-$i\"" -d "{\"name\":\"$(host_name "$i")\"}")" ||
    fail "POST /v1/hosts failed"
  id="$(printf '%s' "$host_json" | json_field id)"
  [[ -n "$id" ]] || fail "POST /v1/hosts returned no id"
  code="$(curl -sf -X POST "$api/v1/hosts/$id/enrolment-code" -H "Authorization: Bearer $token" | json_field code)"
  [[ -n "$code" ]] || fail "no enrolment code"
  host_ids+=("$id")
  codes+=("$code")
done
host_id="${host_ids[0]}"
summary+=("hosts registered: ${host_ids[*]}")

# 5. Build and enrol the agents.
log "building the agent"
# From inside agent/, so rustup uses the toolchain pinned in agent/rust-toolchain.toml; it looks in
# the current directory, not at --manifest-path.
(cd agent && cargo build --release --locked --quiet) >"$out/agent-build.log" 2>&1 ||
  fail "cargo build failed; see private/e2e/agent-build.log"
agent="$root/agent/target/release/idleo-agent"
for i in $(seq 1 "$agents"); do
  rm -rf "$out/agent-$i"
  cat >"$out/agent-$i.toml" <<TOML
ca_root = "$root/private/dev-ca/root.pem"
data_dir = "$out/agent-$i"
log_format = "text"

[control_plane]
host = "localhost"
port = $grpc_port
TOML
  "$agent" --config "$out/agent-$i.toml" enrol --code "${codes[$((i - 1))]}" >>"$out/agent-$i.log" 2>&1 ||
    fail "enrolment failed; see private/e2e/agent-$i.log"
done
unset codes code
summary+=("agents enrolled: $agents")

# 5b. Build the web app (ADR 0018) against this stack and serve it as a host would (web/scripts/serve.ts):
#     the local sign-in adapter at :$web_port; for --web, also a Firebase build with placeholder settings
#     at :$firebase_web_port, for the screens only that adapter has (its sign-in calls answered by fixtures).
log "building the web app"
web_started="$(now_ms)"
(cd web && pnpm install --frozen-lockfile) >"$out/web-build.log" 2>&1 || fail "pnpm install failed; see private/e2e/web-build.log"
(cd web && IDLEO_DEPLOYMENT=local VITE_API_URL="$api" VITE_STORAGE_URL="http://localhost:$storage_port" VITE_AUTH=oidc-mock VITE_OIDC_ISSUER="$sign_in" \
  VITE_OIDC_CLIENT_ID=idleo-local pnpm build) >>"$out/web-build.log" 2>&1 || fail "the web build failed; see private/e2e/web-build.log"
if $web_only; then
  (cd web && IDLEO_DIST=dist-firebase IDLEO_DEPLOYMENT=local VITE_API_URL="$api" VITE_STORAGE_URL="http://localhost:$storage_port" VITE_AUTH=firebase \
    VITE_FIREBASE_API_KEY=placeholder-not-a-key VITE_FIREBASE_AUTH_DOMAIN=placeholder.firebaseapp.com \
    VITE_FIREBASE_PROJECT_ID=placeholder pnpm build) >>"$out/web-build.log" 2>&1 ||
    fail "the Firebase web build failed; see private/e2e/web-build.log"
fi
serve_web() {
  (cd web && IDLEO_DIST="$1" PORT="$2" exec node scripts/serve.ts) >>"$out/web-serve.log" 2>&1 &
  echo $! >"$out/web-$2.pid"
  local deadline=$((SECONDS + 15))
  until curl -sf "http://localhost:$2/sign-in/" >/dev/null 2>&1; do
    ((SECONDS < deadline)) || fail "the web app didn't start on :$2; see private/e2e/web-serve.log"
    sleep 0.2
  done
}
if ! $keep_running || [[ "${E2E_SERVE_WEB:-1}" != 0 ]]; then serve_web dist "$web_port"; fi
$web_only && serve_web dist-firebase "$firebase_web_port"
if ! $keep_running; then
  (cd web && pnpm exec playwright install chromium) >>"$out/web-build.log" 2>&1 || fail "installing Playwright's browser failed"
fi
summary+=("web app built and served: $(seconds_since "$web_started") s")
web_url="http://localhost:$web_port"
# Playwright, with this stack's addresses (web/playwright.config.ts).
web_test() {
  (cd web && WEB_URL="$web_url" API_URL="$api" SIGN_IN_URL="$sign_in" FIREBASE_WEB_URL="http://localhost:$firebase_web_port" \
    pnpm exec playwright test "$@")
}

host_online() {
  curl -sf "$api/v1/hosts/$1" -H "Authorization: Bearer $token" | grep -q "\"online\":$2"
}
# The agent running a host, by its position in host_ids.
agent_of() {
  local i
  for i in "${!host_ids[@]}"; do
    [[ "${host_ids[$i]}" == "$1" ]] && { echo $((i + 1)); return; }
  done
  fail "no agent runs host $1"
}

# 6. Run the agents and wait for the hosts to come online.
agent_started="$(now_ms)"
for i in $(seq 1 "$agents"); do run_agent "$i"; done
deadline=$((SECONDS + 30))
for i in $(seq 1 "$agents"); do
  until host_online "${host_ids[$((i - 1))]}" true; do
    agent_running "$i" || fail "agent $i exited; see private/e2e/agent-$i.log"
    ((SECONDS < deadline)) || fail "host $i didn't come online within 30 s"
    sleep 0.5
  done
done
to_online="$(seconds_since "$agent_started")"
summary+=("agents start to all $agents hosts online: $to_online s: PASS")
host_view="$(curl -sf "$api/v1/hosts/$host_id" -H "Authorization: Bearer $token")"
if $keep_running; then
  IFS='|' read -r model vram _ <<<"${fake_gpus[0]}"
else
  model="IdleO Fake GPU" vram=12282
fi
printf '%s' "$host_view" | grep -q "\"gpus\":\[{\"model\":\"$model\",\"vramMb\":$vram,\"gone\":false}\]" ||
  fail "the host's GPUs aren't listed: $host_view"
summary+=("fake GPU listed on the host ($model, $vram MB): PASS")

# 7. Two renters with ₹500 of free credit each: A for the normal run alone, so its wallet shows exactly
#    that run's charge, and B for the other three. Each host lists at 2 paise a minute and is activated.
if $keep_running; then
  # The account to sign in as, with ₹500 of credit, and the two laptops listed at their prices.
  account_token="$(curl -sf -X POST "$sign_in/token" \
    -d grant_type=client_credentials -d "client_id=$account" -d client_secret=unused -d scope=openid |
    json_field access_token)"
  [[ -n "$account_token" ]] || fail "no token for $account"
  account_id="$(token="$account_token" api_get /v1/me | json_get .id)"
  if [[ "$(token="$account_token" api_get /v1/wallet | json_get .availablePaise)" == 0 ]]; then
    ops grant-credits --user "$account_id" --paise 50000 --reason "'Local stack credit'" --key "local-$account_id" ||
      fail "grant-credits failed; see private/e2e/ops.log"
  fi
  for i in $(seq 1 "$agents"); do
    IFS='|' read -r model vram price <<<"${fake_gpus[$((i - 1))]}"
    curl -sf -X PUT "$api/v1/hosts/${host_ids[$((i - 1))]}/listing" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
      -d "{\"batchPricePaisePerMin\": $price, \"gpuTempCapC\": 85, \"maxConcurrentSessions\": 1}" >/dev/null || fail "PUT listing failed"
    ops activate-host --host "${host_ids[$((i - 1))]}" --reason "'Local stack host'" || fail "activate-host failed; see private/e2e/ops.log"
  done
  printf '\nThe stack is up.\n\n'
  if [[ "${E2E_SERVE_WEB:-1}" != 0 ]]; then
    printf '  Open %s and sign in as %s@example.com (₹%s of credit).\n' "$web_url" "$account" \
      "$(token="$account_token" api_get /v1/wallet | python3 -c 'import json, sys; print("%.2f" % (json.load(sys.stdin)["availablePaise"] / 100))')"
  else
    printf '  Run pnpm dev in web/ and sign in as %s@example.com.\n' "$account"
  fi
  for i in $(seq 1 "$agents"); do
    IFS='|' read -r model vram price <<<"${fake_gpus[$((i - 1))]}"
    printf '  Host %s: %s, %s MB, %s paise a minute, online.\n' "$i" "$model" "$vram" "$price"
  done
  printf '\nThe API, as %s (the token lasts an hour):\n\n' "$account"
  printf "  curl -s -H 'Authorization: Bearer %s' %s/v1/me\n\n" "$account_token" "$api"
  printf 'A new token:\n\n'
  printf "  curl -s -X POST %s/token -d grant_type=client_credentials -d client_id=%s -d client_secret=unused -d scope=openid\n\n" "$sign_in" "$account"
  printf 'Logs: private/e2e/ (control-plane.log, agent-N.log, web-serve.log). Ctrl-C stops everything.\n'
  wait "$control_plane_pid" || true
  exit 0
fi

user_id="$(api_get /v1/me | json_get .id)"
token_b="$(curl -sf -X POST "$sign_in/token" \
  -d grant_type=client_credentials -d "client_id=$user-b" -d client_secret=unused -d scope=openid |
  json_field access_token)"
[[ -n "$token_b" ]] || fail "no token for the second renter"
user_b_id="$(token="$token_b" api_get /v1/me | json_get .id)"
setup_started="$(now_ms)"
# The web journey's renter (ADR 0018): created by a first request with the same invented identity the
# browser signs in with, and given credit before the browser sees it.
web_user="$user-web"
web_token="$(curl -sf -X POST "$sign_in/token" \
  -d grant_type=client_credentials -d "client_id=$web_user" -d client_secret=unused -d scope=openid |
  json_field access_token)"
[[ -n "$web_token" ]] || fail "no token for the web journey's renter"
web_user_id="$(token="$web_token" api_get /v1/me | json_get .id)"
renters=("$web_user_id")
if $web_only; then
  # Lighthouse measures the session screen on a session its renter books (web/e2e/lighthouse.spec.ts).
  lighthouse_token="$(curl -sf -X POST "$sign_in/token" \
    -d grant_type=client_credentials -d "client_id=$user-lighthouse" -d client_secret=unused -d scope=openid |
    json_field access_token)"
  [[ -n "$lighthouse_token" ]] || fail "no token for Lighthouse's renter"
  renters+=("$(token="$lighthouse_token" api_get /v1/me | json_get .id)")
fi
if ! $web_only; then
  # The booking contract's renter (ADR 0019), and one who never gets credit.
  booking_token="$(curl -sf -X POST "$sign_in/token" \
    -d grant_type=client_credentials -d "client_id=$user-booking" -d client_secret=unused -d scope=openid |
    json_field access_token)"
  broke_token="$(curl -sf -X POST "$sign_in/token" \
    -d grant_type=client_credentials -d "client_id=$user-broke" -d client_secret=unused -d scope=openid |
    json_field access_token)"
  [[ -n "$booking_token" && -n "$broke_token" ]] || fail "no tokens for the booking contract's renters"
  booking_user_id="$(token="$booking_token" api_get /v1/me | json_get .id)"
  token="$broke_token" api_get /v1/me >/dev/null
  renters+=("$user_id" "$user_b_id" "$booking_user_id")
fi
for renter in "${renters[@]}"; do
  ops grant-credits --user "$renter" --paise 50000 --reason "'End-to-end test credit'" --key "e2e-$renter" ||
    fail "grant-credits failed; see private/e2e/ops.log"
done
for id in "${host_ids[@]}"; do
  curl -sf -X PUT "$api/v1/hosts/$id/listing" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
    -d '{"batchPricePaisePerMin": 2, "gpuTempCapC": 85, "maxConcurrentSessions": 1}' >/dev/null || fail "PUT listing failed"
  ops activate-host --host "$id" --reason "'End-to-end test host'" || fail "activate-host failed; see private/e2e/ops.log"
done
summary+=("credit for ${#renters[@]} renters, $agents hosts listed and activated: $(seconds_since "$setup_started") s")
if ! $web_only; then
  wallet_before="$(api_get /v1/wallet)"
  [[ "$(printf '%s' "$wallet_before" | json_get .availablePaise)" == 50000 ]] || fail "the wallet isn't ₹500: $wallet_before"
fi

# 8. Four charged sessions at once, one per host (ADR 0017). Each scenario is a subshell; a failure
#    ends it, and the script, with its message.

# Normal run (renter A): 150 s of work at 2 paise a minute is charged as 3 minutes, 6 paise; the host
# earns 4 (80%, rounded down) and the platform keeps 2, in one balanced ledger transaction.
scenario_normal() {
  local id host earnings wallet charged
  id="$(book normal charged-run 10000)"
  await_state "$id" RUNNING 90
  note "normal run: POST to RUNNING $(event_seconds "$id" REQUESTED RUNNING) s"
  [[ "$(api_get /v1/wallet | json_get .reservedPaise)" == 60 ]] || fail "expected 60 paise held for the normal run"
  host="$(api_get "/v1/sessions/$id" | json_get .hostId)"
  await_state "$id" COMPLETED 240
  download_output "$id" "$out/output-normal.json"
  python3 - "$out/output-normal.json" <<'PY' || fail "the output isn't attempt 1 without a resume: $(cat "$out/output-normal.json")"
import json, sys
output = json.load(open(sys.argv[1]))
assert output["attempt"] == 1 and output["resumed_from"] is None, output
PY
  await_closed "$id" 60
  wallet="$(api_get /v1/wallet)"
  [[ "$(printf '%s' "$wallet" | json_get .balancePaise)" == 49994 &&
    "$(printf '%s' "$wallet" | json_get .reservedPaise)" == 0 ]] ||
    fail "the normal run's wallet isn't ₹499.94 with nothing held: $wallet"
  note "normal run wallet: ₹500.00 -> ₹499.94, hold released: $wallet: PASS"
  charged="$(api_get "/v1/sessions/$id" | json_get .chargedPaise)"
  [[ "$charged" == 6 && "$(billed_minutes "$id")" == 3 ]] || fail "the normal run was charged $charged paise, not 3 minutes = 6"
  [[ "$(ledger_balanced "$id")" == t ]] || fail "the normal run's ledger transaction doesn't balance"
  [[ "$(sql "select string_agg(account || ':' || direction || ':' || amount_paise, ',' order by account)
             from billing.ledger_entries where ref_id = '$id'")" == "host_payable:credit:4,platform_revenue:credit:2,renter_wallet:debit:6" ]] ||
    fail "the normal run's ledger transaction isn't 6 from the renter, 4 to the host, 2 to the platform"
  note "normal run charged: 3 minutes = $charged paise, balanced: PASS"
  while IFS= read -r line; do note "  ledger: $line"; done < <(print_ledger "$id")
  earnings="$(api_get "/v1/hosts/$host/earnings")"
  printf '%s' "$earnings" | python3 -c 'import json, sys
earnings = json.load(sys.stdin)
entry = next(e for e in earnings["history"]["entries"] if e["reference"]["id"] == sys.argv[1])
assert entry["kind"] == "session_earning" and entry["direction"] == "credit" and entry["amountPaise"] == 4, entry' "$id" ||
    fail "the host's earnings don't show +4 paise for the normal run: $earnings"
  note "host earnings: +4 paise for the normal run, balance $(printf '%s' "$earnings" | json_get .balancePaise) paise: PASS"
}

# Stop mid-run (renter B): about 70 s in, the renter stops the job; the agent writes a last
# checkpoint and stops, the session completes as stopped, and it's charged the minutes it ran.
scenario_stop() {
  local id running_ms charged minutes
  id="$(book stop long-run 10000)"
  await_state "$id" RUNNING 90
  running_ms="$(event_ms "$id" RUNNING)"
  sleep "$(perl -e 'my $s = ($ARGV[0] + 70000 - $ARGV[1]) / 1000; printf "%.1f", $s > 0 ? $s : 0' "$running_ms" "$(now_ms)")"
  [[ "$(api_post "/v1/sessions/$id/stop" -d '{}' | json_get .state)" == FINISHING ]] || fail "POST /stop didn't move $id to FINISHING"
  await_state "$id" COMPLETED 90
  api_get "/v1/sessions/$id" | python3 -c 'import json, sys
reasons = [e["reason"] for e in json.load(sys.stdin)["events"]]
assert reasons[-2:] == ["renter_stopped", "stopped"], reasons' || fail "the stopped session's events don't end renter_stopped, stopped"
  await_closed "$id" 60
  [[ "$(checkpointed_after_stop "$id")" == t ]] || fail "the stopped session wrote no checkpoint after the stop"
  charged="$(api_get "/v1/sessions/$id" | json_get .chargedPaise)"
  minutes="$(sql "select count(*) from metering.usage_minutes where session_id = '$id' and agent_reported")"
  [[ "$charged" == $((2 * minutes)) && "$(billed_minutes "$id")" == "$minutes" ]] ||
    fail "the stopped session was charged $charged paise for $minutes minutes run"
  [[ "$(ledger_balanced "$id")" == t ]] || fail "the stopped session's ledger doesn't balance"
  note "stop at ~70 s: COMPLETED (stopped) after a final checkpoint; charged $charged paise for the $minutes minutes it ran: PASS"
}

# Host loss (renter B): kill -9 the agent once minute 0 is reported and a checkpoint is past it. The
# lease lapses, attempt 2 resumes from the newest complete checkpoint, attempt 1's minutes after that
# checkpoint are waived, and attempt 2's are charged.
scenario_loss() {
  local id host index killed_at lost_at resumed_seq charged first second waiver late
  id="$(book loss charged-run 10000)"
  await_state "$id" RUNNING 90
  host="$(api_get "/v1/sessions/$id" | json_get .hostId)"
  index="$(agent_of "$host")"
  local deadline=$((SECONDS + 120))
  until [[ "$(sql "select count(*) > 0 from metering.usage_minutes where session_id = '$id' and agent_reported and agent_attempt = 1")" == t &&
    "$(sql "select count(*) > 0 from sessions.checkpoints c join sessions.attempts a on a.session_id = c.session_id and a.attempt = 1
            where c.session_id = '$id' and c.attempt = 1 and c.completed_at > a.running_at + interval '60 seconds'")" == t ]]; do
    ((SECONDS < deadline)) || fail "session $id never reported minute 0 and a checkpoint after it"
    sleep 0.2
  done
  killed_at="$(now_ms)"
  kill -KILL "$(cat "$out/agent-$index.pid")"
  await_events "$id" HOST_LOST 60
  lost_at="$(event_ms "$id" HOST_LOST)"
  note "host loss: kill -9 to HOST_LOST $(perl -e 'printf "%.2f", ($ARGV[1] - $ARGV[0]) / 1000' "$killed_at" "$lost_at") s (lease $lease_ttl, plus a 2 s transit allowance): PASS"
  [[ "$(api_get "/v1/sessions/$id" | json_get .attempt)" == 2 ]] || fail "the lost session isn't on attempt 2"
  run_agent "$index"
  await_state "$id" COMPLETED 240
  download_output "$id" "$out/output-resumed.json"
  resumed_seq="$(sql "select max(step) from sessions.checkpoints where session_id = '$id' and attempt = 1")"
  python3 - "$out/output-resumed.json" "$id" "$resumed_seq" <<'PY' ||
import json, sys
output, session, seq = json.load(open(sys.argv[1])), sys.argv[2], int(sys.argv[3])
assert output["attempt"] == 2, output
assert output["resumed_from"] == {"prefix": f"sessions/{session}/attempt-1/checkpoints/", "seq": seq}, output
PY
    fail "the output isn't attempt 2 resumed from checkpoint $resumed_seq: $(cat "$out/output-resumed.json")"
  note "downloaded output (resumed run): $(tr -d '\n ' <"$out/output-resumed.json"): PASS"
  # Fencing: attempt 2 ran only after attempt 1 was declared lost, which happens only once its lease
  # and the transit allowance have passed.
  [[ "$(sql "select (select running_at from sessions.attempts where session_id = '$id' and attempt = 2)
    > (select at from sessions.session_events where session_id = '$id' and to_state = 'HOST_LOST')")" == t ]] ||
    fail "attempt 2 ran before attempt 1 was declared lost"
  note "fencing: attempt 2 ran only after attempt 1's lease lapsed: PASS"
  await_closed "$id" 60
  waiver="$(sql "select 'checkpoint_at ' || checkpoint_at || ', waived_from ' || waived_from || ', refunded ' || refunded_minutes
                 || ' minutes = ' || refunded_paise || ' paise (txn ' || refund_txn || ')'
                 from metering.waivers where session_id = '$id' and attempt = 1")"
  [[ -n "$waiver" ]] || fail "attempt 1 of the lost session has no waiver"
  late="$(sql "select count(*) from metering.usage_minutes u join metering.waivers w on w.session_id = u.session_id and w.attempt = 1
               where u.session_id = '$id' and u.agent_attempt = 1 and u.billed and u.refund_txn is null
                 and u.minute_at + interval '1 minute' > w.waived_from")"
  [[ "$late" == 0 ]] || fail "$late minutes of attempt 1 after its last checkpoint were charged"
  first="$(billed_minutes "$id" 1)"
  second="$(billed_minutes "$id" 2)"
  charged="$(api_get "/v1/sessions/$id" | json_get .chargedPaise)"
  ((first >= 1 && second >= 1)) || fail "expected minutes charged on both attempts, got $first and $second"
  [[ "$charged" == $((2 * (first + second))) ]] || fail "the lost session was charged $charged paise for $((first + second)) minutes"
  [[ "$(ledger_balanced "$id")" == t ]] || fail "the lost session's ledger doesn't balance"
  note "host loss charged: attempt 1 $first minutes (none after its checkpoint), attempt 2 $second minutes, total $charged paise: PASS"
  note "  waiver: $waiver"
  while IFS= read -r line; do note "  ledger: $line"; done < <(print_ledger "$id")
}

# Spending cap (renter B): 6 paise at 2 paise a minute. The session is stopped with max_spend_reached
# after a checkpoint, and charged exactly the minutes it ran, never past the cap.
scenario_cap() {
  local id charged
  id="$(book cap long-run 6)"
  await_state "$id" RUNNING 90
  await_events "$id" FINISHING 300
  await_state "$id" COMPLETED 90
  api_get "/v1/sessions/$id" | python3 -c 'import json, sys
reasons = [e["reason"] for e in json.load(sys.stdin)["events"]]
assert reasons[-2:] == ["max_spend_reached", "stopped"], reasons' || fail "the capped session's events don't end max_spend_reached, stopped"
  await_closed "$id" 60
  grep -q "stopping an attempt after a checkpoint session_id=$id attempt=1 reason=\"max_spend_reached\"" "$out"/agent-*.log ||
    fail "the capped session's agent wasn't told the spending cap was reached"
  [[ "$(checkpointed_after_stop "$id")" == t ]] || fail "the capped session wrote no checkpoint after the stop"
  charged="$(api_get "/v1/sessions/$id" | json_get .chargedPaise)"
  ((charged <= 6)) || fail "the capped session was charged $charged paise, past its 6 paise cap"
  [[ "$charged" == $((2 * $(billed_minutes "$id"))) &&
    "$(billed_minutes "$id")" == "$(sql "select count(*) from metering.usage_minutes where session_id = '$id' and agent_reported")" ]] ||
    fail "the capped session wasn't charged exactly the minutes it ran"
  [[ "$(ledger_balanced "$id")" == t ]] || fail "the capped session's ledger doesn't balance"
  note "spending cap: COMPLETED after max_spend_reached and a final checkpoint; charged $charged paise (cap 6) for the minutes it ran: PASS"
}

# The web journeys (ADR 0018, ADR 0021, web/e2e/journey.spec.ts), one after another on whichever host is
# free: Home following a session booked through the API; on a laptop the whole request (a service, its
# settings, a 12 MiB upload, the quote without a ceiling, Request a GPU, finding and found, the session live
# to settled, the output's SHA-256, the server's ceiling and the hold it took) and Run again; no GPU free;
# not enough credit; cancelling an upload; the event stream dropping and coming
# back; and stopping a session on a phone.
scenario_web() {
  E2E_WEB_USER="$web_user" E2E_JOURNEY_REPORT="$out/web-journey.json" SKIP_VISUAL=1 \
    web_test --project=journey --reporter=line >>"$out/web-journey.log" 2>&1 ||
    fail "the web journeys failed; see private/e2e/web-journey.log"
  note "web journeys: $(grep -Eo '[0-9]+ passed' "$out/web-journey.log" | tail -1) (Home, a request on a laptop, Run again, no GPU free, not enough credit, cancelled upload, stream drop, stop on a phone): PASS"
  note "web request: $(python3 -c 'import json,sys; n=json.load(open(sys.argv[1])); print("Request a GPU to found %ss and to RUNNING %ss, upload %s MiB/s, ceiling chosen %d paise/min (highest fitting %s), hold quoted %d and held %d paise (30 min at the ceiling %d), charged %s (API %d paise), SHA-256 %s" % (n["requestToFoundSeconds"], n["requestToRunningSeconds"], n["uploadMiBPerSecond"], n["ceilingChosenPaisePerMin"], n["highestFittingPaisePerMin"], n["quotedHoldPaise"], n["heldWhileRunningPaise"], n["thirtyMinutesAtCeilingPaise"], n["chargedOnScreen"], n["chargedByApiPaise"], "matches" if n["fileSha256"] == n["outputSha256"] else "DIFFERS"))' "$out/web-journey.json")"
}

# The booking contract (ADR 0019), on its own host: upload, quote, book with parameters and the upload as the
# input, follow the event stream to settled, read the logs, check the output's hash and parameters and the
# charge, and prove three errors by their types.
scenario_booking() {
  local file size parts part_size upload quote hold fits session stream_pid deleted
  token="$booking_token"
  file="$out/booking-input.bin"
  size=$((12 * 1024 * 1024))
  head -c "$size" /dev/urandom >"$file"
  local_sha="$(shasum -a 256 "$file" | cut -d' ' -f1)"
  upload="$(upload_file "$file" "e2e input.bin" booking-upload)" || exit 1
  parts="$(json_get .files.0.partCount <"$out/upload-booking-upload.json")"
  part_size="$(json_get .upload.partSizeBytes <"$out/upload-booking-upload.json")"
  ((parts == 3)) || fail "a 12 MiB upload in 5 MiB parts should take 3 parts, not $parts"
  note "booking: uploaded $size bytes in $parts parts of $part_size bytes, straight to storage: ready"

  local body="{\"templateId\": \"echo-input\", \"maxPricePaisePerMin\": 5, \"maxSpendPaise\": 10000,
    \"params\": {\"greeting\": \"hello\", \"repeat\": 3}, \"inputs\": {\"file\": {\"uploadId\": \"$upload\"}}}"
  quote="$(api_post /v1/session-quotes -d "$body")" || fail "POST /v1/session-quotes failed"
  hold="$(printf '%s' "$quote" | json_get .holdPaise)"
  fits="$(printf '%s' "$quote" | json_get .hostsFitNow)"
  [[ "$hold" == 150 ]] || fail "the quote holds $hold paise, not min(30 x 5, 10000) = 150"
  ((fits >= 1)) || fail "the quote found no host that fits: $quote"
  note "booking: quote holdPaise $hold = min(30 minutes x 5 paise, 10000 cap); hostsFitNow $fits; enoughCredit $(printf '%s' "$quote" | json_get .enoughCredit): PASS"

  session="$(api_post /v1/sessions -H "Idempotency-Key: \"$user-booking-session\"" -d "$body" | json_get .id)"
  [[ -n "$session" ]] || fail "booking echo-input failed"
  # The event stream, until the session is settled and the stream closes; each state with when it arrived.
  curl -sN --max-time 300 "$api/v1/sessions/$session/events" -H "Authorization: Bearer $token" \
    -H 'Accept: text/event-stream' | python3 -c 'import json, sys, time
states, logs, name, settled_at, completed_at = [], 0, None, None, None
for line in sys.stdin:
    line = line.rstrip("\n")
    if line.startswith("event:"):
        name = line[6:].strip()
    elif line.startswith("data:") and name == "session":
        view, now = json.loads(line[5:].strip()), time.time()
        if not states or states[-1]["state"] != view["state"]:
            states.append({"state": view["state"], "at": now})
        if view["state"] == "COMPLETED" and completed_at is None:
            completed_at = now
        if view["settled"] and settled_at is None:
            settled_at = now
    elif line.startswith("data:") and name == "logs":
        logs += 1
json.dump({"states": states, "logs_events": logs, "completed_at": completed_at, "settled_at": settled_at,
           "closed_at": time.time()}, open(sys.argv[1], "w"))' "$out/booking-stream.json" &
  stream_pid=$!
  await_state "$session" RUNNING 90
  deleted="$(curl -s -X DELETE "$api/v1/uploads/$upload" -H "Authorization: Bearer $token")"
  [[ "$(printf '%s' "$deleted" | json_get .type)" == /problems/upload-in-use ]] ||
    fail "deleting the upload during the run wasn't upload-in-use: $deleted"
  wait "$stream_pid" || fail "the event stream failed"
  python3 - "$out/booking-stream.json" <<'PY' || fail "the stream didn't follow the session to settled: $(cat "$out/booking-stream.json")"
import json, sys
stream = json.load(open(sys.argv[1]))
states = [s["state"] for s in stream["states"]]
assert states[-1] == "COMPLETED" and "RUNNING" in states, states
assert stream["settled_at"] is not None and stream["completed_at"] is not None, stream
PY
  note "booking: stream states $(python3 -c 'import json, sys
s = json.load(open(sys.argv[1])); t0 = s["states"][0]["at"]
print(", ".join("%s +%.1fs" % (x["state"], x["at"] - t0) for x in s["states"]))' "$out/booking-stream.json"); COMPLETED to settled $(python3 -c 'import json, sys
s = json.load(open(sys.argv[1])); print("%.1f" % (s["settled_at"] - s["completed_at"]))' "$out/booking-stream.json") s; closed after settled; $(python3 -c 'import json, sys
print(json.load(open(sys.argv[1]))["logs_events"])' "$out/booking-stream.json") logs events: PASS"

  api_get "/v1/sessions/$session/logs?limit=100" >"$out/booking-logs.json" || fail "GET /logs failed"
  python3 - "$out/booking-logs.json" "$local_sha" <<'PY' || fail "the logs don't name the parameters and the input's hash: $(head -c 2000 "$out/booking-logs.json")"
import json, sys
page = json.load(open(sys.argv[1]))
text = "".join(chunk["text"] for chunk in page["chunks"])
assert page["complete"], page
assert "parameter SESSION_PARAM_GREETING=hello" in text and "parameter SESSION_PARAM_REPEAT=3" in text, text
assert sys.argv[2] in text, text
print(len(page["chunks"]), len(text.splitlines()))
PY
  note "booking: logs $(python3 -c 'import json, sys
page = json.load(open(sys.argv[1])); text = "".join(c["text"] for c in page["chunks"])
print("%d chunks, %d lines, complete" % (len(page["chunks"]), len(text.splitlines())))' "$out/booking-logs.json"), with the parameters and the hash: PASS"

  download_output "$session" "$out/output-booking.json"
  python3 - "$out/output-booking.json" "$local_sha" "$size" <<'PY' || fail "the output doesn't prove the exact input and parameters: $(cat "$out/output-booking.json")"
import json, sys
output = json.load(open(sys.argv[1]))
[seen] = output["inputs"]["file"]
assert seen["sha256"] == sys.argv[2] and seen["size_bytes"] == int(sys.argv[3]), output
assert seen["path"] == "e2e_input.bin", output
assert output["params"] == {"SESSION_PARAM_GREETING": "hello", "SESSION_PARAM_REPEAT": "3"}, output
assert output["echo"] == ["hello", "hello", "hello"], output
PY
  note "booking: sha256 local $local_sha"
  note "booking: sha256 job   $(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["inputs"]["file"][0]["sha256"])' "$out/output-booking.json"): equal: PASS"
  note "booking: params echoed $(python3 -c 'import json, sys
o = json.load(open(sys.argv[1])); print(json.dumps(o["params"]), "echo", json.dumps(o["echo"]))' "$out/output-booking.json"): PASS"

  local charged minutes
  charged="$(api_get "/v1/sessions/$session" | json_get .chargedPaise)"
  minutes="$(billed_minutes "$session")"
  [[ "$charged" == $((2 * minutes)) && "$minutes" -ge 1 ]] || fail "the booking was charged $charged paise for $minutes minutes"
  [[ "$(ledger_balanced "$session")" == t ]] || fail "the booking's ledger doesn't balance"
  note "booking: charged $charged paise for $minutes started minute(s) at 2 paise: PASS"

  local refused
  refused="$(curl -s -X POST "$api/v1/sessions" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: \"$user-booking-wrong\"" \
    -d "{\"templateId\": \"run-code-simulated\", \"maxPricePaisePerMin\": 5, \"maxSpendPaise\": 10000, \"inputs\": {\"code\": {\"url\": \"https://example.com/code.zip\"}}}" | json_get .type)"
  [[ "$refused" == /problems/input-not-accepted ]] || fail "run-code-simulated with a link for its code wasn't input-not-accepted: $refused"
  refused="$(curl -s -X POST "$api/v1/sessions" -H "Authorization: Bearer $broke_token" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: \"$user-broke-1\"" \
    -d '{"templateId": "cuda-smoke-test", "maxPricePaisePerMin": 5, "maxSpendPaise": 10000}' | json_get .type)"
  [[ "$refused" == /problems/insufficient-credit ]] || fail "booking with no credit wasn't insufficient-credit: $refused"
  note "booking: errors by type: input-not-accepted, insufficient-credit, upload-in-use: PASS"
}

# The simulated fine-tune (templates/development.yaml), on its own host: the renter's model, a generated folder of 3
# files, and a small JSONL of 40 examples uploaded, fine-tune-simulated booked with the "standard" preset and a LoRA
# rank, followed to COMPLETED, and its adapter downloaded and read as a safetensors file, with the training log's
# figures matching what was asked.
scenario_finetune() {
  local file model upload model_upload session outputs url name model_bytes
  token="$booking_token"
  file="$out/finetune-examples.jsonl"
  for i in $(seq 1 40); do
    printf '{"prompt": "Question %d: what is %d plus %d?", "completion": "%d"}\n' "$i" "$i" "$i" $((2 * i))
  done >"$file"
  model="$out/finetune-model"
  mkdir -p "$model"
  printf '{"model_type": "llama", "hidden_size": 64, "num_hidden_layers": 2}\n' >"$model/config.json"
  printf '{"version": "1.0", "model": {"type": "BPE", "vocab": {}}}\n' >"$model/tokenizer.json"
  head -c 12582912 /dev/urandom >"$model/model-00001-of-00001.safetensors"
  model_bytes="$(cat "$model"/* | wc -c | tr -d ' ')"
  model_upload="$(upload_folder "finetune-model" finetune-model \
    config.json "$model/config.json" tokenizer.json "$model/tokenizer.json" \
    model-00001-of-00001.safetensors "$model/model-00001-of-00001.safetensors")" || exit 1
  upload="$(upload_file "$file" "examples.jsonl" finetune-upload)" || exit 1
  session="$(api_post /v1/sessions -H "Idempotency-Key: \"$user-finetune-session\"" \
    -d "{\"templateId\": \"fine-tune-simulated\", \"maxPricePaisePerMin\": 5, \"maxSpendPaise\": 10000,
         \"presets\": {\"thoroughness\": \"standard\"}, \"params\": {\"lora_rank\": \"8\"},
         \"inputs\": {\"model\": {\"uploadId\": \"$model_upload\"}, \"data\": {\"uploadId\": \"$upload\"}}}" | json_get .id)"
  [[ -n "$session" ]] || fail "booking fine-tune-simulated failed"
  await_state "$session" RUNNING 90
  local host vram free_disk
  host="$(api_get "/v1/sessions/$session" | json_get .hostId)"
  vram="$(api_get "/v1/sessions/$session" | json_get .vramNeededMb)"
  free_disk="$(presence "$host" free_disk_mb | tr -d '\r')"
  [[ "$vram" == 8000 ]] || fail "the fine-tune asked for $vram MB of GPU memory, not the 8 GB class its small model needs"
  [[ "$free_disk" =~ ^[0-9]+$ ]] && ((free_disk >= 4520)) ||
    fail "the fine-tune's host reports $free_disk MB free, less than its files and scratch disk need"
  note "simulated fine-tune: GPU memory asked $vram MB (the 8 GB class, from the model's size); host $host, $free_disk MB free disk; requested to running $(event_seconds "$session" REQUESTED RUNNING) s"
  await_state "$session" COMPLETED 240
  outputs="$(api_get "/v1/sessions/$session/outputs")" || fail "GET /outputs failed for the fine-tune"
  [[ "$(printf '%s' "$outputs" | python3 -c 'import json, sys
print(",".join(sorted(f["path"] for f in json.load(sys.stdin)["files"])))')" == "adapter_model.safetensors,training_log.json" ]] ||
    fail "the fine-tune's outputs aren't the adapter and its training log: $outputs"
  for name in adapter_model.safetensors training_log.json; do
    url="$(printf '%s' "$outputs" | python3 -c 'import json, sys
print(next(f["url"] for f in json.load(sys.stdin)["files"] if f["path"] == sys.argv[1]))' "$name")"
    curl -sf "$url" -o "$out/finetune-$name" || fail "downloading the fine-tune's $name failed"
  done
  python3 - "$out/finetune-adapter_model.safetensors" "$out/finetune-training_log.json" <<'CHECK' ||
import json, struct, sys
data = open(sys.argv[1], "rb").read()
(length,) = struct.unpack("<Q", data[:8])
header = json.loads(data[8 : 8 + length])
tensors = {name: t for name, t in header.items() if name != "__metadata__"}
assert len(tensors) == 8 and all(t["dtype"] == "F32" and 8 in t["shape"] for t in tensors.values()), header
assert max(t["data_offsets"][1] for t in tensors.values()) == len(data) - 8 - length, "the tensors fill the file"
assert header["__metadata__"]["base_model"] == "your uploaded model (3 files)", header
log = json.load(open(sys.argv[2]))
assert log["training_examples"] == 40 and log["epochs"] == 3 and log["lora_rank"] == 8, log
assert len(log["steps"]) == 15 and log["steps"][-1]["loss"] < log["steps"][0]["loss"], log
CHECK
    fail "the fine-tune's adapter or training log isn't what was asked for"
  api_get "/v1/sessions/$session/logs?limit=100" | python3 -c 'import json, sys
text = "".join(c["text"] for c in json.load(sys.stdin)["chunks"])
assert "your model: 3 files, %s bytes in /work/input/model/" % sys.argv[1] in text, text
assert "  model-00001-of-00001.safetensors (12582912 bytes)" in text and "  config.json (" in text, text
assert "  tokenizer.json (" in text and "epoch 3/3 step 15/15 loss" in text, text' "$model_bytes" ||
    fail "the fine-tune's log doesn't show its epochs and steps"
  note "simulated fine-tune: a 3-file model folder (config.json, tokenizer.json, model-00001-of-00001.safetensors; $model_bytes bytes) and 40 examples, standard (3 epochs), rank 8; downloaded adapter_model.safetensors, $(wc -c <"$out/finetune-adapter_model.safetensors" | tr -d ' ') bytes, 8 F32 tensors; loss $(python3 -c 'import json, sys
log = json.load(open(sys.argv[1])); print("%.4f to %.4f" % (log["steps"][0]["loss"], log["final_loss"]))' "$out/finetune-training_log.json"); the log shows every step: PASS"
}

# 8 (--web). The web app's checks (ADR 0018), against this stack: every screen and state with axe and
#    the visual baselines, the Firebase screens, zero layout shift, the journey; then Lighthouse's
#    budgets and the first-load size budget of the production (Firebase) build.
if $web_only; then
  checks_started="$(now_ms)"
  baseline="${VISUAL_BASELINE_DIR:-$root/web/visual-baseline}"
  snapshots="${E2E_UPDATE_SNAPSHOTS:-}"
  if [[ -z "$snapshots" ]]; then
    if [[ -n "$(ls -A "$baseline" 2>/dev/null)" ]]; then snapshots=none; else snapshots=all; fi
  fi
  if [[ "${VISUAL_COMPARE:-}" == 1 ]]; then
    summary+=("visual baselines: $baseline ($([[ $snapshots == none ]] && echo "compared" || echo "written: $snapshots"))")
  else
    summary+=("visual comparison: off (VISUAL_COMPARE=1 turns it on); axe, layout shift and the overflow check run")
  fi
  E2E_WEB_USER="$web_user" VISUAL_BASELINE_DIR="$baseline" web_test --project='screens-*' --project=journey \
    --update-snapshots="$snapshots" >"$out/web-playwright.log" 2>&1 ||
    fail "the web app's browser tests failed; see private/e2e/web-playwright.log and web/playwright-report"
  summary+=("web: $(grep -Eo '[0-9]+ passed' "$out/web-playwright.log" | tail -1) (every screen and state, light and dark, 390 and 1280 px; axe; layout shift; Firebase screens; journey) in $(seconds_since "$checks_started") s: PASS")
  (cd web && rm -rf .lighthouseci .lighthouseci-desktop && E2E_LIGHTHOUSE_USER="$user-lighthouse" WEB_URL="$web_url" \
    API_URL="$api" SIGN_IN_URL="$sign_in" pnpm lighthouse) >"$out/web-lighthouse.log" 2>&1 || fail "a Lighthouse budget failed; see private/e2e/web-lighthouse.log"
  while IFS= read -r line; do summary+=("lighthouse $line"); done < <(python3 - <<'PY'
import json
for page, m in json.load(open("web/.lighthouseci/summary.json")).items():
    inp = ", INP %d ms" % m["inpMs"] if "inpMs" in m else ""
    runs = m["lcpRunsMs"]
    lcp = "LCP %d ms" % m["lcpMs"] if len(runs) == 1 else "LCP %d ms (median of %s)" % (m["lcpMs"], ", ".join(map(str, runs)))
    print("%s: %s, TBT %d ms, CLS %s, accessibility %d%s" % (page, lcp, m["tbtMs"], m["cls"], m["accessibility"], inp))
PY
)
  summary+=("lighthouse budgets (mobile, the median of 3 runs: LCP <= 2000 ms, TBT <= 150 ms, CLS 0, accessibility 100; INP <= 200 ms): PASS")
  first_load="$(cd web && IDLEO_DIST=dist-firebase node scripts/first-load.ts | tail -1)" || fail "the first-load JavaScript budget failed"
  summary+=("${first_load# }: PASS")
fi

if ! $web_only; then
scenarios_started="$(now_ms)"
scenarios=(normal stop loss cap web booking finetune)
scenario_pids=()
for scenario in "${scenarios[@]}"; do
  : >"$out/summary-$scenario.txt"
  if [[ $scenario == normal || $scenario == web || $scenario == booking || $scenario == finetune ]]; then
    ( "scenario_$scenario" ) &
  else
    ( token="$token_b"; "scenario_$scenario" ) &
  fi
  scenario_pids+=($!)
done
failed=""
for i in "${!scenarios[@]}"; do
  wait "${scenario_pids[$i]}" || failed+=" ${scenarios[$i]}"
  while IFS= read -r line; do summary+=("$line"); done <"$out/summary-${scenarios[$i]}.txt"
done
[[ -z "$failed" ]] || fail "scenarios failed:$failed"
summary+=("seven scenarios side by side (four charged, the web journey, the booking contract and the simulated fine-tune): $(seconds_since "$scenarios_started") s")

wallet_b="$(token="$token_b" api_get /v1/wallet)"
(($(printf '%s' "$wallet_b" | json_get .balancePaise) >= 0)) && [[ "$(printf '%s' "$wallet_b" | json_get .reservedPaise)" == 0 ]] ||
  fail "renter B's wallet is negative or still holds credit: $wallet_b"
summary+=("renter B's wallet after three sessions: $wallet_b: PASS")
grep -q "resumed from a checkpoint" "$out"/agent-*.log || fail "no agent logged resuming from a checkpoint"
refreshes="$(cat "$out"/agent-*.log | grep -c "storage credentials refreshed" || true)"
((refreshes > 0)) || fail "no agent swapped in fresh storage credentials"
summary+=("storage credentials refreshed in running jobs: $refreshes times: PASS")
for field in secret_access_key session_token X-Amz-Security-Token X-Amz-Credential; do
  if grep -q "$field" "$out"/agent-*.log "$out/control-plane.log"; then
    fail "a storage credential field ($field) appears in the logs"
  fi
done
summary+=("no storage credentials in the agent or control-plane logs: PASS")
fi

# 9. The nightly wallet check, now: every wallet, reservation and hold matches the ledger.
ops reconcile-wallets || fail "the wallet check found mismatches; see private/e2e/ops.log"
grep -q "^0 mismatches" "$out/ops.log" || fail "the wallet check didn't report 0 mismatches"
summary+=("wallet reconciliation after the run: $(grep '^ledger:' "$out/ops.log" | tail -1); 0 mismatches: PASS")

# 10. Stop the agents and wait for the hosts to go offline.
stopped_at="$(now_ms)"
for i in $(seq 1 "$agents"); do kill -TERM "$(cat "$out/agent-$i.pid")"; done
deadline=$((SECONDS + 30))
for i in $(seq 1 "$agents"); do
  # An agent this shell started is waited for, with its exit status; the one restarted from the
  # host-loss subshell isn't this shell's child, and is watched until it's gone.
  pid="$(cat "$out/agent-$i.pid")"
  agent_status=0
  wait "$pid" 2>/dev/null || agent_status=$?
  if ((agent_status == 127)); then
    while kill -0 "$pid" 2>/dev/null; do
      ((SECONDS < deadline)) || fail "agent $i didn't exit within 30 s of SIGTERM"
      sleep 0.2
    done
  elif ((agent_status != 0)); then
    fail "agent $i exited with $agent_status after SIGTERM"
  fi
  until host_online "${host_ids[$((i - 1))]}" false; do
    ((SECONDS < deadline)) || fail "host $i didn't go offline within 30 s"
    sleep 0.5
  done
done
to_offline="$(seconds_since "$stopped_at")"
# Presence lives 15 s after the last heartbeat; a clean close deletes it at once.
if perl -e 'exit($ARGV[0] < 5 ? 0 : 1)' "$to_offline"; then
  how="the clean close"
else
  how="the 15 s presence expiry, so a clean close didn't remove presence"
fi
summary+=("SIGTERM to all hosts offline: $to_offline s, from $how: PASS")
