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
# downloads its adapter. An eighth has the only 24 GB GPU but too little free disk for any session, so it
# is never offered one (the web journeys check it). It checks storage's CORS for the web app. Once the
# sessions are done, it checks hosting (ADR 0023) on the same hosts: it renames two of them after laptops, pauses
# the rest and the cheaper laptop, books, and sees the session go to the other laptop; resumes the cheaper laptop and
# sees the next one go back to it; reads a host's running session and telemetry and the sessions it ran; and
# checks that GET /v1/earnings equals the sum of the ledger's entries. Then it runs the wallet check and watches
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
#                                               stack, one agent and the small-disk host, then every
#                                               screen and state with
#                                               axe and visual baselines, the Firebase screens, the
#                                               journey, Lighthouse's budgets and the size budget
#
# Ports, from the environment: E2E_HTTP_PORT (8080), E2E_GRPC_PORT (9443), LOCAL_SIGN_IN_PORT (8099),
# LOCAL_STORAGE_PORT (9100), LOCAL_MAIL_SMTP_PORT (1025), LOCAL_MAIL_WEB_PORT (8025), E2E_WEB_PORT (3000),
# E2E_FIREBASE_WEB_PORT (3001).
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
mail_smtp_port="${LOCAL_MAIL_SMTP_PORT:-1025}"
mail_web_port="${LOCAL_MAIL_WEB_PORT:-8025}"
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
api_patch() { curl -sf -X PATCH "$api$1" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' "${@:2}"; }
# Sets a host's listing: $1 the host, $2 its price in paise a minute. It keeps the least disk free (10 GB), so a
# small machine still has room for the test sessions' files.
list_host() {
  curl -sf -X PUT "$api/v1/hosts/$1/listing" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
    -d "{\"batchPricePaisePerMin\": $2, \"gpuTempCapC\": 85, \"maxConcurrentSessions\": 1, \"keepFreeGb\": 10}" >/dev/null ||
    fail "PUT listing failed for host $1"
}
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
# Access is by invitation (ADR 0024): the run's accounts are activated with codes an operator made, as a person
# would enter one. An account that's already active is left alone, so this also works with E2E_INVITE_ONLY=false.
invite_codes=()
invite_next=0
activate_user() {
  local code="${invite_codes[$invite_next]:-}"
  [[ -n "$code" ]] || fail "no invite code left for the run's accounts"
  invite_next=$((invite_next + 1))
  curl -sf -X POST "$api/v1/me/activate" -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -d "{\"code\":\"$code\"}" >/dev/null || fail "an account couldn't be activated with its invite code"
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
# --keep-running, each agent's fake GPU is a laptop's (fake_gpus below). Otherwise the last agent is the
# small-disk host: the only 24 GB GPU, on a disk with 1,000 MB free beyond the 10 GB its owner keeps free
# (list_host), less than any template's scratch space, so it counts as idle but matching never offers it a
# session (ADRs 0022, 0023; web/e2e/journey.spec.ts checks it).
run_agent() {
  # Every host reports 200 GB free unless it's the small-disk one: a CI runner's real free disk, less the 10 GB each
  # host keeps for its owner, may not hold even a small session's files (ADR 0023).
  local gpu=(--fake-free-disk-mb 200000)
  if $keep_running; then
    IFS='|' read -r model vram _ <<<"${fake_gpus[$(($1 - 1))]}"
    gpu+=(--fake-gpu-model "$model" --fake-gpu-vram-mb "$vram")
  elif (($1 == agents)); then
    gpu=(--fake-gpu-model "RTX 4090" --fake-gpu-vram-mb 24564 --fake-free-disk-mb 11240)
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
# Email (ADR 0026): what Mailpit has kept, read through its API.
mail_api="http://localhost:$mail_web_port/api/v1"
# Money as the control plane writes it, for amounts under ₹1,000: 9 paise is ₹0.09.
rupees() { printf '₹%d.%02d' $(($1 / 100)) $(($1 % 100)); }
# Waits up to $3 s (60) for an email to $1 whose text holds $2, and prints it as JSON: subject, text, html.
await_email() {
  local deadline=$((SECONDS + ${3:-60})) found
  while ((SECONDS < deadline)); do
    found="$(python3 - "$mail_api" "$1" "$2" <<'PY' 2>/dev/null || true
import json, sys, urllib.request
api, to, needle = sys.argv[1:4]
def get(path):
    return json.load(urllib.request.urlopen(api + path, timeout=10))
for m in get("/messages?limit=500")["messages"]:
    if any(t["Address"].lower() == to.lower() for t in m["To"]):
        full = get("/message/" + m["ID"])
        if needle in full["Text"]:
            print(json.dumps({"subject": full["Subject"], "text": full["Text"], "html": full["HTML"]}))
            break
PY
)"
    if [[ -n "$found" ]]; then printf '%s' "$found"; return 0; fi
    sleep 1
  done
  printf 'e2e: FAIL: no email to %s holding "%s" within %s s\n' "$1" "$2" "${3:-60}" >&2
  return 1
}
# How many emails to $1 hold $2.
count_emails() {
  python3 - "$mail_api" "$1" "$2" <<'PY'
import json, sys, urllib.request
api, to, needle = sys.argv[1:4]
def get(path):
    return json.load(urllib.request.urlopen(api + path, timeout=10))
print(sum(1 for m in get("/messages?limit=500")["messages"]
          if any(t["Address"].lower() == to.lower() for t in m["To"])
          and needle in get("/message/" + m["ID"])["Text"]))
PY
}
# Checks an email ($1, JSON) against its subject's ending ($2) and the text it must hold (the rest); plain text and
# HTML both, no images or tracking, and the footer saying why it came.
check_email() {
  local mail=$1 ending=$2
  shift 2
  python3 - "$mail" "$ending" "$@" <<'PY' || fail "an email isn't what the control plane promises: $mail"
import json, sys
mail, ending, held = json.loads(sys.argv[1]), sys.argv[2], sys.argv[3:]
assert mail["subject"].endswith(ending), mail["subject"]
for text in held:
    assert text in mail["text"], (text, mail["text"])
assert "Change what we email you in Account: http://localhost" in mail["text"], mail["text"]
for forbidden in ("<img", "<script", "<link", "url("):
    assert forbidden not in mail["html"], forbidden
PY
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
if $web_only; then agents=2; elif $keep_running; then agents=${#fake_gpus[@]}; else agents=8; fi
control_plane_pid=""
summary=()

cleanup() {
  local status=$?
  set +e
  for pid_file in "$out"/web-*.pid "$out"/proxy-*.pid; do
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

for port in "$http_port" "$grpc_port" "$sign_in_port" "$storage_port" "$mail_smtp_port" "$mail_web_port" "$web_port" "$firebase_web_port"; do
  if port_in_use "$port"; then
    fail "port $port is already in use; stop what holds it, or move the stack with E2E_HTTP_PORT, E2E_GRPC_PORT, LOCAL_SIGN_IN_PORT, LOCAL_STORAGE_PORT, LOCAL_MAIL_SMTP_PORT, LOCAL_MAIL_WEB_PORT, E2E_WEB_PORT or E2E_FIREBASE_WEB_PORT"
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
  # Email goes to Mailpit from compose.yaml (ADR 0026), and its links to this run's web app.
  export LOCAL_MAIL_SMTP_PORT="$mail_smtp_port" LOCAL_MAIL_WEB_PORT="$mail_web_port"
  export NOTIFICATIONS_WEB_APP_URL="http://localhost:$web_port"
  # The web app's origin, which may call the API from a browser (ADR 0018).
  export WEB_ALLOWED_ORIGINS="http://localhost:$web_port"
  # The renter limits (ADR 0025) are lifted for the run: its few accounts are a test harness, not renters. They
  # poll every half second, several scenarios to an account at once (far past 60 requests a minute), and book far
  # more than 2 sessions in their first hour. The control plane's own tests hold every limit (RenterLimitsTests).
  export LIMITS_REQUESTS_PER_MINUTE=100000 LIMITS_LOG_READS_PER_MINUTE=100000 LIMITS_CLI_LOGINS_PER_HOUR=100000
  export SESSIONS_LIMITS_NEW_PER_HOUR=1000 SESSIONS_LIMITS_NEW_PER_HOUR_FIRST_DAY=1000 SESSIONS_LIMITS_AT_ONCE=1000
  # Access is by invitation, as in production (ADR 0024): the run's accounts are activated with codes below.
  # E2E_INVITE_ONLY=false lets anyone in, to look at the stack by hand without a code.
  export IDENTITY_INVITE_ONLY="${E2E_INVITE_ONLY:-true}"
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

# 3a. Invite codes (docs/runbooks/invites.md): one for each account the run makes, and two spare for the web
#     journey that signs in as a new person and enters one.
log "making invite codes for the run's accounts"
ops create-invites --count 10 --note "'e2e run'" --expires 1d || fail "create-invites failed; see private/e2e/ops.log"
invite_codes=($(grep -o 'IDLE-[A-Z0-9]\{4\}-[A-Z0-9]\{4\}' "$out/ops.log" | tail -n 10))
((${#invite_codes[@]} == 10)) || fail "create-invites didn't print 10 codes; see private/e2e/ops.log"
export E2E_INVITE_CODE="${invite_codes[7]}"
activate_user "$token"

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
if ! $keep_running; then
  export E2E_SMALL_DISK_HOST="${host_ids[$((agents - 1))]}"
fi

# 5. Build and enrol the agents.
log "building the agent"
# From inside agent/, so rustup uses the toolchain pinned in agent/rust-toolchain.toml; it looks in
# the current directory, not at --manifest-path.
(cd agent && cargo build --release --locked --quiet) >"$out/agent-build.log" 2>&1 ||
  fail "cargo build failed; see private/e2e/agent-build.log"
agent="$root/agent/target/release/idleo-agent"
if ! $keep_running && ! $web_only; then
  # The renter CLI the CLI scenario drives (cli/rust-toolchain.toml pins the same toolchain).
  log "building the CLI"
  (cd cli && cargo build --release --locked --quiet) >"$out/cli-build.log" 2>&1 ||
    fail "cargo build of the CLI failed; see private/e2e/cli-build.log"
  # The Python SDK the SDK scenario runs (ADR 0030); uv fetches the pinned Python itself.
  log "installing the Python SDK"
  command -v uv >/dev/null || fail "uv isn't installed; the Python SDK scenario needs it (https://docs.astral.sh/uv/)"
  (cd sdk/python && uv sync --locked --quiet) >"$out/sdk-build.log" 2>&1 ||
    fail "uv sync of the Python SDK failed; see private/e2e/sdk-build.log"
fi
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
  activate_user "$account_token"
  account_id="$(token="$account_token" api_get /v1/me | json_get .id)"
  if [[ "$(token="$account_token" api_get /v1/wallet | json_get .availablePaise)" == 0 ]]; then
    ops grant-credits --user "$account_id" --paise 50000 --reason "'Local stack credit'" --key "local-$account_id" ||
      fail "grant-credits failed; see private/e2e/ops.log"
  fi
  for i in $(seq 1 "$agents"); do
    IFS='|' read -r model vram price <<<"${fake_gpus[$((i - 1))]}"
    list_host "${host_ids[$((i - 1))]}" "$price"
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
  printf '  Email (every message the stack sends, kept, none delivered): http://localhost:%s\n' "$mail_web_port"
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
activate_user "$token_b"
user_b_id="$(token="$token_b" api_get /v1/me | json_get .id)"
setup_started="$(now_ms)"
# The web journey's renter (ADR 0018): created by a first request with the same invented identity the
# browser signs in with, and given credit before the browser sees it.
web_user="$user-web"
web_token="$(curl -sf -X POST "$sign_in/token" \
  -d grant_type=client_credentials -d "client_id=$web_user" -d client_secret=unused -d scope=openid |
  json_field access_token)"
[[ -n "$web_token" ]] || fail "no token for the web journey's renter"
activate_user "$web_token"
# The web journey that has no credit signs in as its own invented person, who is never given any.
web_broke_token="$(curl -sf -X POST "$sign_in/token" \
  -d grant_type=client_credentials -d "client_id=$web_user-broke" -d client_secret=unused -d scope=openid |
  json_field access_token)"
[[ -n "$web_broke_token" ]] || fail "no token for the web journey's renter without credit"
activate_user "$web_broke_token"
web_user_id="$(token="$web_token" api_get /v1/me | json_get .id)"
renters=("$web_user_id")
if $web_only; then
  # Lighthouse measures the session screen on a session its renter books (web/e2e/lighthouse.spec.ts).
  lighthouse_token="$(curl -sf -X POST "$sign_in/token" \
    -d grant_type=client_credentials -d "client_id=$user-lighthouse" -d client_secret=unused -d scope=openid |
    json_field access_token)"
  [[ -n "$lighthouse_token" ]] || fail "no token for Lighthouse's renter"
  activate_user "$lighthouse_token"
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
  activate_user "$booking_token"
  activate_user "$broke_token"
  booking_user_id="$(token="$booking_token" api_get /v1/me | json_get .id)"
  token="$broke_token" api_get /v1/me >/dev/null
  # The renter CLI's account: its sign-in is approved below with this account's browser token.
  cli_token="$(curl -sf -X POST "$sign_in/token" \
    -d grant_type=client_credentials -d "client_id=$user-cli" -d client_secret=unused -d scope=openid |
    json_field access_token)"
  [[ -n "$cli_token" ]] || fail "no token for the CLI's renter"
  activate_user "$cli_token"
  cli_user_id="$(token="$cli_token" api_get /v1/me | json_get .id)"
  renters+=("$user_id" "$user_b_id" "$booking_user_id" "$cli_user_id")
fi
for renter in "${renters[@]}"; do
  ops grant-credits --user "$renter" --paise 50000 --reason "'End-to-end test credit'" --key "e2e-$renter" ||
    fail "grant-credits failed; see private/e2e/ops.log"
done
for id in "${host_ids[@]}"; do
  list_host "$id" 2
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
  mail="$(await_email "$user@example.com" "/session/?id=$id#outputs")" || exit 1
  check_email "$mail" " finished" "It cost $(rupees "$charged")." "Open it: $web_url/session/?id=$id#outputs"
  [[ "$(count_emails "$user@example.com" "/session/?id=$id#outputs")" == 1 ]] || fail "the normal run was emailed more than once"
  note "normal run emailed: \"$(printf '%s' "$mail" | json_get .subject)\", It cost $(rupees "$charged"). (one email): PASS"
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
  # A session its renter stopped says nothing: they did it themselves (ADR 0026).
  await_closed "$id" 60
  sleep 3
  [[ "$(count_emails "$user-b@example.com" "/session/?id=$id")" == 0 ]] || fail "a session its renter stopped was emailed"
  [[ "$(sql "select count(*) from notifications.notifications where dedupe_key = 'session-ended:$id'")" == 0 ]] ||
    fail "a session its renter stopped made a notification"
  note "stop at ~70 s: no email and no notification for a session the renter stopped: PASS"
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
  # The machine's owner is emailed that it went offline during a session, and never told who rented it or what
  # ran; the renter hears it is moving, in the app only (ADR 0026).
  mail="$(await_email "$user@example.com" "went offline during a session")" || exit 1
  check_email "$mail" " went offline during a session" "It was running a session when it lost its connection"
  [[ "$(printf '%s' "$mail" | python3 -c 'import json, sys
mail = json.load(sys.stdin); text = mail["text"] + mail["html"]
print(all(word not in text for word in (sys.argv[1], sys.argv[2], "Fine-tune", "charged-run")))' "$id" "$user-b")" == True ]] ||
    fail "the owner's email names the session or its renter: $mail"
  [[ "$(sql "select count(*) from notifications.notifications where dedupe_key = 'host-lost:$id:1' and email_wanted")" == 0 &&
    "$(sql "select count(*) from notifications.notifications where dedupe_key = 'host-lost:$id:1' and in_app")" == 1 ]] ||
    fail "the renter's host-lost message isn't in the app only"
  note "host loss told: the owner emailed \"$(printf '%s' "$mail" | json_get .subject)\" with no renter and no session in it; the renter told in the app only: PASS"
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
  mail="$(await_email "$user-b@example.com" "/session/?id=$id")" || exit 1
  check_email "$mail" "Stopped: it reached your spending cap" "It cost $(rupees "$charged")."
  note "spending cap emailed: \"$(printf '%s' "$mail" | json_get .subject)\", It cost $(rupees "$charged").: PASS"
}

# The web journeys (ADR 0018, ADR 0021, ADR 0022, web/e2e/journey.spec.ts), one after another on whichever
# host is free: Home following a session booked through the API; on a laptop, from Home through "Fine-tune a
# model" (a generated 3-file model folder and 40 examples uploaded, Quick, a budget chip, Request a GPU, live to
# settled, the adapter downloaded and read, the server's ceiling and the hold it took) and Run again from My
# files; an upload interrupted by a reload carrying on from its last part; the small-disk host never offered
# a session; not enough credit; cancelling an upload; the event stream dropping and coming back; and stopping
# a session on a phone.
scenario_web() {
  local host free_disk
  E2E_WEB_USER="$web_user" E2E_JOURNEY_REPORT="$out/web-journey.json" SKIP_VISUAL=1 \
    web_test --project=journey --reporter=line >>"$out/web-journey.log" 2>&1 ||
    fail "the web journeys failed; see private/e2e/web-journey.log"
  note "web journeys: $(grep -Eo '[0-9]+ passed' "$out/web-journey.log" | tail -1) (Home, the fine-tune from Home with a folder upload, Run again, a resumed upload, the small-disk host, not enough credit, cancelled upload, stream drop, stop on a phone): PASS"
  host="$(json_get .fineTune.hostId <"$out/web-journey.json")"
  free_disk="$(presence "$host" free_disk_mb | tr -d '\r')"
  note "web fine-tune: $(python3 -c 'import json,sys; n=json.load(open(sys.argv[1]))["fineTune"]; print("model folder %s (%d bytes), uploaded in %ss; GPU memory asked %d MB; host %s with %s MB free disk; Request a GPU to RUNNING %ss (server %ss); ceiling %d paise/min (highest fitting %s), hold quoted %d and held %d paise; adapter %d bytes; charged %d paise" % (", ".join("%s %d" % f for f in n["modelFiles"].items()), n["modelBytes"], n["uploadSeconds"], n["vramNeededMb"], n["hostId"], sys.argv[2], n["requestToRunningSeconds"], n["serverRunningAfterSeconds"], n["ceilingPaisePerMin"], n["highestFittingPaisePerMin"], n["quotedHoldPaise"], n["heldPaise"], n["adapterBytes"], n["chargedPaise"]))' "$out/web-journey.json" "$free_disk")"
  note "web resumed upload: $(python3 -c 'import json,sys; n=json.load(open(sys.argv[1]))["resumedUpload"]; print("%d bytes in %d parts; %d taken before the reload, then only the %d others sent (the first of them part %d); %ss in all" % (n["sizeBytes"], n["parts"], n["takenBeforeTheReload"], n["sentAfterTheReload"], n["resumedFromPart"], n["totalSeconds"]))' "$out/web-journey.json")"
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
  # The renter is emailed that it finished, with what it cost and a link to its outputs (ADR 0026).
  await_closed "$session" 60
  local charged mail
  charged="$(api_get "/v1/sessions/$session" | json_get .chargedPaise)"
  [[ "$charged" -gt 0 ]] || fail "the simulated fine-tune was charged nothing"
  mail="$(await_email "$user-booking@example.com" "/session/?id=$session#outputs")" || exit 1
  check_email "$mail" " finished" "It cost $(rupees "$charged"). Your outputs are ready to download." "Open it: $web_url/session/?id=$session#outputs"
  [[ "$(count_emails "$user-booking@example.com" "/session/?id=$session#outputs")" == 1 ]] || fail "the simulated fine-tune was emailed more than once"
  note "simulated fine-tune emailed: \"$(printf '%s' "$mail" | json_get .subject)\", It cost $(rupees "$charged"). (one email, Mailpit): PASS"
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
  # The public pages (ADR 0029), signed out, on their own stricter budgets.
  while IFS= read -r line; do summary+=("lighthouse public $line"); done < <(python3 - <<'PY'
import json
for page, m in json.load(open("web/.lighthouseci/public-summary.json")).items():
    print("%s: LCP %d ms (median of %s), TBT %d ms, CLS %s, accessibility %d" % (page, m["lcpMs"], ", ".join(map(str, m["lcpRunsMs"])), m["tbtMs"], m["cls"], m["accessibility"]))
PY
)
  summary+=("lighthouse public pages (mobile, the median of 3 runs: LCP <= 1500 ms, TBT <= 100 ms, CLS 0, accessibility 100, SEO 100): PASS")
  first_load="$(cd web && IDLEO_DIST=dist-firebase node scripts/first-load.ts | tail -1)" || fail "the first-load JavaScript budget failed"
  summary+=("${first_load# }: PASS")
  site_load="$(cd web && IDLEO_DIST=dist-firebase node scripts/site-size.ts | tail -1)" || fail "the public pages' first-load budget failed"
  summary+=("${site_load# }: PASS")
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
summary+=("seven scenarios side by side (four charged, the web journeys, the booking contract and the simulated fine-tune), beside the small-disk host: $(seconds_since "$scenarios_started") s")

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

# Hosting (ADR 0023), on the hosts the scenarios used, once they're idle: two of them become "laptops", the cheaper
# preferred by the scheduler (cheaper scores higher), and every other host is paused, so the choice is between the two.
# Pausing the preferred laptop sends the next session to the other; resuming it brings sessions back. Along the way
# the preferred host shows its running session and telemetry, and afterwards the sessions it ran. Finally the owner's
# earnings equal the ledger's host_payable entries, host by host and in all.
scenario_hosting() {
  local preferred="${host_ids[0]}" other="${host_ids[1]}" i session chosen sessions=() view
  api_patch "/v1/hosts/$preferred" -d '{"name": "RTX 4080 Laptop"}' >/dev/null || fail "renaming host $preferred failed"
  api_patch "/v1/hosts/$other" -d '{"name": "RTX 4060 Laptop"}' >/dev/null || fail "renaming host $other failed"
  [[ "$(api_get "/v1/hosts/$preferred" | json_get .name)" == "RTX 4080 Laptop" ]] || fail "the rename didn't stick"
  list_host "$preferred" 3
  list_host "$other" 4
  for ((i = 2; i < agents; i++)); do
    [[ "$(api_post "/v1/hosts/${host_ids[$i]}/pause" -d '{}' | json_get .paused)" == True ]] || fail "pausing host ${host_ids[$i]} didn't pause it"
  done
  # Books a long run, with the hosts as they are, and follows it to RUNNING on its host ($chosen); with "watch",
  # reads that host's running session and telemetry; then stops it and waits until metering has closed it
  # ($session, and added to $sessions).
  book_and_stop() {
    session="$(book "$1" long-run 10000)"
    [[ -n "$session" ]] || fail "booking $1 failed"
    await_state "$session" RUNNING 90
    chosen="$(api_get "/v1/sessions/$session" | json_get .hostId)"
    if [[ "${2:-}" == watch ]]; then
      view="$(api_get "/v1/hosts/$chosen")"
      printf '%s' "$view" | python3 -c 'import json, sys
host = json.load(sys.stdin)
current = host["currentSession"]
assert current and current["serviceName"] and current["runningLabel"] and current["earnedSoFarPaise"] >= 0, host
assert "renter" not in json.dumps(host), host
assert host["online"] and host["onlineSince"] and host["agentVersion"] and not host["updateRequired"], host' ||
        fail "the host's view doesn't show its running session: $view"
      api_get "/v1/hosts/$chosen/telemetry" | python3 -c 'import json, sys
telemetry = json.load(sys.stdin)
assert telemetry["now"] and telemetry["now"]["gpus"], telemetry
assert len(telemetry["lastHour"]) >= 1, telemetry' || fail "the host's telemetry has no reading"
    fi
    [[ "$(api_post "/v1/sessions/$session/stop" -d '{}' | json_get .state)" == FINISHING ]] || fail "POST /stop didn't move $session to FINISHING"
    await_state "$session" COMPLETED 90
    await_closed "$session" 60
    sessions+=("$session")
  }
  book_and_stop hosting-before watch
  [[ "$chosen" == "$preferred" ]] || fail "with nothing paused, session $session went to $chosen, not the preferred laptop $preferred"
  note "hosting: nothing paused: session $session went to the cheaper laptop (RTX 4080 Laptop, $preferred): PASS"
  api_post "/v1/hosts/$preferred/pause" -d '{}' >/dev/null || fail "pausing the preferred laptop failed"
  [[ "$(api_get "/v1/hosts/$preferred" | json_get .paused)" == True ]] || fail "the preferred laptop isn't paused"
  book_and_stop hosting-paused
  [[ "$chosen" == "$other" ]] || fail "with the preferred laptop paused, session $session went to $chosen, not $other"
  note "hosting: RTX 4080 Laptop ($preferred) paused: session $session went to RTX 4060 Laptop ($other): PASS"
  [[ "$(api_post "/v1/hosts/$preferred/resume" | json_get .paused)" == False ]] || fail "resuming the preferred laptop didn't resume it"
  book_and_stop hosting-resumed
  [[ "$chosen" == "$preferred" ]] || fail "after resuming, session $session went to $chosen, not $preferred"
  note "hosting: resumed: session $session went back to RTX 4080 Laptop: PASS"
  api_get "/v1/hosts/$other/sessions" | python3 -c 'import json, sys
ran = json.load(sys.stdin)["sessions"]
assert ran and ran[0]["id"] == sys.argv[1] and ran[0]["outcome"] == "stopped", ran
assert "renter" not in json.dumps(ran), ran' "${sessions[1]}" || fail "the host's sessions don't list the stopped session"
  note "hosting: the 4060's sessions list shows ${sessions[1]} as stopped, with no renter: PASS"
  # Owner-wide earnings are the ledger's host_payable entries: in all, and for each machine.
  local earnings ledger
  earnings="$(api_get /v1/earnings)"
  ledger="$(sql "select owner_id || '|' || coalesce(sum(case direction when 'credit' then amount_paise else -amount_paise end), 0)
                 from billing.ledger_entries where account = 'host_payable' group by owner_id")"
  printf '%s' "$earnings" | python3 -c 'import json, sys
earnings = json.load(sys.stdin)
ledger = dict(line.split("|") for line in sys.argv[1].split() if line)
hosts = set(sys.argv[2].split())
expected = {h: int(v) for h, v in ledger.items() if h in hosts}
machines = {m["hostId"]: m["earnedPaise"] for m in earnings["machines"]}
assert earnings["totalPaise"] == sum(expected.values()) and earnings["totalPaise"] > 0, (earnings["totalPaise"], expected)
assert {h: v for h, v in machines.items() if v} == {h: v for h, v in expected.items() if v}, (machines, expected)
assert 0 < earnings["weekPaise"] <= earnings["totalPaise"] and earnings["payoutsStart"] == "paid-pilot", earnings
assert "renter" not in json.dumps(earnings), earnings
print(earnings["totalPaise"], sum(expected.values()))' "$ledger" "${host_ids[*]}" >"$out/earnings-check.txt" ||
    fail "GET /v1/earnings doesn't match the ledger: $earnings; ledger: $ledger"
  note "hosting: GET /v1/earnings = $(cut -d' ' -f1 "$out/earnings-check.txt") paise; the host_payable ledger entries of the same hosts sum to $(cut -d' ' -f2 "$out/earnings-check.txt") paise: PASS"
}
scenario="hosting"
: >"$out/summary-hosting.txt"
scenario_hosting

# The owner's journeys in a browser (web/e2e/hosting-journey.spec.ts), on a machine the journey adds itself: its name
# to connected with a real agent enrolled from the code on the page, paused and resumed, its price changed and the
# quote's range with it, an earning session read on three screens, and removing it. Once everything else is over, so
# the machine it adds changes nothing for the scenarios. The one step an operator does by hand is activate-host.sh.
scenario_hosting_web() {
  local host sum report="$out/hosting-journey.json"
  E2E_WEB_USER="$web_user" E2E_AGENT="$agent" E2E_AGENT_CA="$root/private/dev-ca/root.pem" E2E_GRPC_PORT="$grpc_port" \
    E2E_ACTIVATE_HOST="$root/scripts/e2e/activate-host.sh" LOCAL_SIGN_IN_PORT="$sign_in_port" \
    COMPOSE_PROJECT_NAME="$compose_project" E2E_HOSTING_REPORT="$report" SKIP_VISUAL=1 \
    web_test --project=hosting-journey --reporter=line >>"$out/hosting-journey.log" 2>&1 ||
    fail "the hosting journeys failed; see private/e2e/hosting-journey.log"
  note "hosting journeys: $(grep -Eo '[0-9]+ passed' "$out/hosting-journey.log" | tail -1) (a machine added from its name to connected with a real agent, activated, paused and resumed, its price changed, an earning session, removed): PASS"
  host="$(json_get .addedMachine.hostId <"$report")"
  note "hosting journey: pausing the machine took the hosts that fit a quote from $(json_get .pause.hostsFitBefore <"$report") to $(json_get .pause.hostsFitWhilePaused <"$report") and resuming brought it back; its price went from $(json_get .price.before <"$report") to $(json_get .price.after <"$report") paise a minute and the quote's top price followed: PASS"
  sum="$(sql "select coalesce(sum(case direction when 'credit' then amount_paise else -amount_paise end), 0)
              from billing.ledger_entries where account = 'host_payable' and owner_id = '$host'")"
  [[ "$sum" == "$(json_get .earnings.totalPaise <"$report")" && "$sum" == "$(json_get .removed.earningsKept <"$report")" ]] ||
    fail "the journey's earnings ($(json_get .earnings.totalPaise <"$report") paise) aren't the ledger's $sum for host $host"
  note "hosting journey: session $(json_get .earnings.sessionId <"$report") charged $(json_get .earnings.chargedPaise <"$report") paise; the Hosting tab, Earnings and Sessions it ran showed $sum paise, the host_payable ledger sum of host $host; still $sum after removing it: PASS"
}
scenario_hosting_web
while IFS= read -r line; do summary+=("$line"); done <"$out/summary-hosting.txt"

# Access by invitation and the account in a browser (web/e2e/account-journey.spec.ts, ADR 0024): a new person signs in
# and is asked for a code, a wrong one is refused, the script's spare one lets them in, a code works once, and Account
# saves a name and a choice that survive a reload, shows the locked kinds locked, and signs out.
scenario_account_web() {
  E2E_WEB_USER="$web_user" SKIP_VISUAL=1 \
    web_test --project=account-journey --reporter=line >>"$out/account-journey.log" 2>&1 ||
    fail "the account journeys failed; see private/e2e/account-journey.log"
  note "account journeys: $(grep -Eo '[0-9]+ passed' "$out/account-journey.log" | tail -1) (first sign-in asks for an invite code, a wrong code is refused, the right one lets the person in, a code works once; Account saves a name and a choice across a reload, shows the money and safety kinds locked, and signs out): PASS"
}
scenario="account"
: >"$out/summary-account.txt"
scenario_account_web
while IFS= read -r line; do summary+=("$line"); done <"$out/summary-account.txt"

# The renter CLI (cli/, ADR 0025) against this stack, as a person would use it: sign in (approved in the "browser" with
# the account's own token, as the web page would), run the simulated fine-tune with a model folder and examples,
# follow it to the end, read its logs, download the adapter, list and revoke keys, and sign out.
scenario_cli() {
  local idleo="$root/cli/target/release/idleo" work="$out/cli-work" config="$out/cli-config" code login_id
  local run_started run_seconds session adapter parts
  rm -rf "$work" "$config" && mkdir -p "$work/my-model"
  export IDLEO_API_URL="$api" IDLEO_CONFIG_DIR="$config" IDLEO_NO_KEYCHAIN=1

  # Signed out first: a command that needs a key says so, with the exit code a script can branch on.
  local code_out=0
  "$idleo" whoami >/dev/null 2>&1 || code_out=$?
  [[ "$code_out" == 5 ]] || fail "idleo whoami with no key exited $code_out, not 5"

  # login: the CLI prints a link and a code and waits; the account approves that code; the CLI is handed its key.
  "$idleo" login --no-browser --device-name "e2e-laptop" >"$work/login.out" 2>&1 &
  local login_pid=$!
  for _ in $(seq 1 30); do grep -q "check that the code is" "$work/login.out" 2>/dev/null && break; sleep 0.5; done
  code="$(grep -o 'check that the code is [A-Z-]*' "$work/login.out" | awk '{print $NF}')"
  [[ -n "$code" ]] || fail "idleo login printed no code: $(cat "$work/login.out")"
  login_id="$(curl -sf -H "Authorization: Bearer $cli_token" "$api/v1/cli-logins?code=$code" | json_get .id)"
  [[ -n "$login_id" ]] || fail "the browser couldn't find the CLI's sign-in by its code $code"
  curl -sf -X POST -H "Authorization: Bearer $cli_token" "$api/v1/cli-logins/$login_id/approve" >/dev/null ||
    fail "approving the CLI's sign-in failed"
  wait "$login_pid" || fail "idleo login failed: $(cat "$work/login.out")"
  grep -q "^Signed in as $user-cli@example.com" "$work/login.out" || fail "idleo login didn't say who signed in: $(cat "$work/login.out")"
  [[ "$("$idleo" --json whoami | json_get .email)" == "$user-cli@example.com" ]] || fail "idleo whoami names the wrong account"
  note "cli login: the code $code approved with the account's token, the CLI handed its key once (a file only its owner can read), whoami names the account: PASS"

  # The reading commands.
  "$idleo" credit | grep -q "Available  ₹500.00" || fail "idleo credit doesn't say ₹500.00: $("$idleo" credit)"
  "$idleo" templates | grep -q "fine-tune-simulated" || fail "idleo templates doesn't list the fine-tune"
  "$idleo" templates show fine-tune-simulated | grep -q -- "--input NAME=PATH" || fail "idleo templates show doesn't say how to give inputs"
  for gb in "8 GB" "12 GB" "16 GB" "24 GB"; do
    "$idleo" gpus | grep -q "^$gb" || fail "idleo gpus lacks the $gb class"
  done
  [[ "$("$idleo" run no-such-template 2>/dev/null; echo $?)" == 2 ]] || fail "idleo run with an unknown template didn't exit 2"

  # run: a model folder (three files, 12 MiB of weights) and 40 examples, uploaded, booked, followed to the end.
  printf '{"model_type": "llama", "hidden_size": 64, "num_hidden_layers": 2}\n' >"$work/my-model/config.json"
  printf '{"version": "1.0", "model": {"type": "BPE", "vocab": {}}}\n' >"$work/my-model/tokenizer.json"
  head -c 12582912 /dev/urandom >"$work/my-model/model-00001-of-00001.safetensors"
  python3 -c '
import json
for i in range(40):
    print(json.dumps({"text": f"### Question {i}\nWhat is {i}+{i}?\n### Answer\n{i * 2}"}))' >"$work/train.jsonl"
  run_started="$(now_ms)"
  "$idleo" --json run fine-tune-simulated --input "model=$work/my-model" --input "data=$work/train.jsonl" \
    >"$work/run.json" 2>"$work/run.err" || fail "idleo run failed (exit $?): $(tail -5 "$work/run.err")"
  run_seconds="$(( ($(now_ms) - run_started) / 1000 ))"
  session="$(json_get .session.id <"$work/run.json")"
  [[ "$(json_get .session.state <"$work/run.json")" == COMPLETED ]] || fail "the CLI's session didn't complete: $(head -c 400 "$work/run.json")"
  grep -q "saving the adapter" "$work/run.err" || fail "idleo run didn't follow the logs live: $(tail -3 "$work/run.err")"
  grep -q "uploaded 12.0 MB" "$work/run.err" || fail "idleo run didn't say it uploaded the model folder"
  adapter="$(python3 -c 'import json, sys
files = json.load(open(sys.argv[1]))["outputs"]["files"]
print([f["sizeBytes"] for f in files if f["path"] == "adapter_model.safetensors"][0])' "$work/run.json")" ||
    fail "the CLI's run result doesn't list the adapter"

  # The session as the API has it: the CLI booked it for this account, and its charge is the ledger's.
  token="$cli_token" api_get "/v1/sessions/$session" >"$work/session.json"
  [[ "$(json_get .state <"$work/session.json")" == COMPLETED ]] || fail "the API says the CLI's session isn't completed"
  local charged to_running
  charged="$(json_get .chargedPaise <"$work/session.json")"
  [[ "$charged" == "$(json_get .session.chargedPaise <"$work/run.json")" ]] || fail "the CLI's charge differs from the API's"
  to_running="$(token="$cli_token" event_seconds "$session" REQUESTED RUNNING)"

  # logs, status, ls, outputs.
  "$idleo" logs "$session" | grep -q "saving the adapter" || fail "idleo logs doesn't hold the whole log"
  "$idleo" status "$session" | grep -q "completed" || fail "idleo status doesn't say completed"
  "$idleo" ls --all | grep -q "$session" || fail "idleo ls --all doesn't list the session"
  "$idleo" outputs "$session" | grep -q "adapter_model.safetensors" || fail "idleo outputs doesn't list the adapter"
  "$idleo" outputs "$session" --download "$work/out" >/dev/null 2>&1 || fail "idleo outputs --download failed"
  [[ -s "$work/out/adapter_model.safetensors" ]] || fail "the adapter wasn't downloaded"
  # A partly downloaded file carries on from where it stopped, and a whole one isn't fetched again.
  local whole; whole="$(wc -c <"$work/out/adapter_model.safetensors" | tr -d ' ')"
  head -c 10000 "$work/out/adapter_model.safetensors" >"$work/out/adapter_model.safetensors.idleo-part"
  rm "$work/out/adapter_model.safetensors"
  "$idleo" outputs "$session" --download "$work/out" >/dev/null 2>&1 || fail "the resumed download failed"
  [[ "$(wc -c <"$work/out/adapter_model.safetensors" | tr -d ' ')" == "$whole" ]] || fail "the resumed download isn't whole"
  python3 - "$work/out/adapter_model.safetensors" <<'PY' || fail "the downloaded adapter isn't a safetensors file with its 8 tensors"
import json, struct, sys
data = open(sys.argv[1], "rb").read()
header = json.loads(data[8 : 8 + struct.unpack("<Q", data[:8])[0]])
tensors = [name for name in header if name != "__metadata__"]
assert len(tensors) == 8, tensors
PY

  # A booking whose answer is lost books once: a proxy hands every try of POST /v1/sessions to the API and throws its
  # answer away, so the CLI gives up not knowing; the same command run again, over the same model folder, finds the
  # same Idempotency-Key (made from what was asked for) and the same, ready upload, and gets back the one session.
  local proxy_port=$((http_port + 17)) dropped="$work/dropped" before after lost_session
  before="$(token="$cli_token" api_get "/v1/sessions?limit=100" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["sessions"]))')"
  python3 "$root/scripts/e2e/drop-answer-proxy.py" "$proxy_port" "$api" 3 "$dropped" >"$out/proxy-drop.log" 2>&1 &
  echo $! >"$out/proxy-drop.pid"
  for _ in $(seq 1 50); do curl -s -o /dev/null "http://127.0.0.1:$proxy_port/v1/templates" && break; sleep 0.1; done
  local lost_exit=0
  IDLEO_API_URL="http://127.0.0.1:$proxy_port" "$idleo" run fine-tune-simulated --input "model=$work/my-model" \
    --input "data=$work/train.jsonl" --detach >"$work/lost.out" 2>"$work/lost.err" || lost_exit=$?
  [[ "$lost_exit" == 7 ]] || fail "idleo run with its answers lost exited $lost_exit, not 7: $(tail -3 "$work/lost.err")"
  [[ "$(cat "$dropped")" == 3 ]] || fail "the proxy dropped $(cat "$dropped" 2>/dev/null) answers, not 3"
  grep -q "won't book twice" "$work/lost.err" || fail "idleo run didn't say running it again won't book twice"
  "$idleo" run fine-tune-simulated --input "model=$work/my-model" --input "data=$work/train.jsonl" \
    --detach >"$work/rerun.out" 2>"$work/rerun.err" || fail "the re-run after a lost answer failed: $(tail -3 "$work/rerun.err")"
  grep -q "already uploaded" "$work/rerun.err" || fail "the re-run uploaded the model again: $(head -5 "$work/rerun.err")"
  lost_session="$(head -1 "$work/rerun.out")"
  after="$(token="$cli_token" api_get "/v1/sessions?limit=100" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["sessions"]))')"
  [[ "$after" == $((before + 1)) ]] || fail "a lost answer and a re-run booked $((after - before)) sessions, not 1"
  kill -TERM "$(cat "$out/proxy-drop.pid")" 2>/dev/null; rm -f "$out/proxy-drop.pid"
  "$idleo" stop "$lost_session" >/dev/null 2>&1 || "$idleo" cancel "$lost_session" >/dev/null 2>&1 || true
  note "cli lost answer: three tries of a booking reached the API and their answers were thrown away (exit 7, \"won't book twice\"); the same command again reused the ready upload and the same key, and got back session $lost_session: 1 session booked, not 2: PASS"

  # An upload cut off part-way carries on: kill the CLI once storage has taken a few parts of a 1.5 GB file, run
  # the same command again, and it picks up where it stopped instead of sending it all again.
  mkdir -p "$work/big-model" && head -c 1572864000 /dev/zero >"$work/big-model/weights.safetensors"
  printf '{}\n' >"$work/big-model/config.json"
  "$idleo" run fine-tune-simulated --input "model=$work/big-model" --input "data=$work/train.jsonl" --detach \
    >"$work/cut.out" 2>"$work/cut.err" &
  local cut_pid=$!
  parts=0
  for _ in $(seq 1 300); do
    parts="$(python3 - "$config/uploads" <<'PY'
import glob, json, sys
total = 0
for path in glob.glob(sys.argv[1] + "/*.json"):
    try:
        total += sum(len(v) for v in json.load(open(path))["parts"].values())
    except (OSError, ValueError, KeyError):
        pass
print(total)
PY
)"
    ((parts >= 30)) && break
    sleep 0.1
  done
  kill -9 "$cut_pid" 2>/dev/null || true
  wait "$cut_pid" 2>/dev/null || true
  ((parts >= 30)) || fail "the CLI's big upload never got 30 parts in before it was cut off ($parts)"
  [[ ! -s "$work/cut.out" ]] || fail "a cut-off run printed a session id: $(cat "$work/cut.out")"
  "$idleo" run fine-tune-simulated --input "model=$work/big-model" --input "data=$work/train.jsonl" --detach \
    >"$work/resumed.out" 2>"$work/resumed.err" || fail "idleo run didn't carry on after being cut off: $(tail -3 "$work/resumed.err")"
  grep -q "carrying on from where it stopped" "$work/resumed.err" || fail "the second run started the upload over: $(head -5 "$work/resumed.err")"
  local resumed_session
  resumed_session="$(head -1 "$work/resumed.out")"
  "$idleo" stop "$resumed_session" >/dev/null || "$idleo" cancel "$resumed_session" >/dev/null || true
  rm -rf "$work/big-model"

  # The Python SDK (ADR 0030), signed in with the key the CLI just stored in the same config folder: the gate example
  # as a plain script, then the same account's other views of what it made.
  local sdk_out sdk_session sdk_charged
  sdk_out="$(cd "$root/sdk/python" && uv run --quiet python "$root/scripts/e2e/sdk-gate.py" "$work" "$user-cli@example.com" 2>"$work/sdk.err")" ||
    fail "the Python SDK's gate failed: $(tail -8 "$work/sdk.err")"
  sdk_session="$(printf '%s' "$sdk_out" | json_get .session)"
  [[ "$(printf '%s' "$sdk_out" | json_get .state)" == COMPLETED ]] || fail "the SDK's session didn't complete: $sdk_out"
  token="$cli_token" api_get "/v1/sessions/$sdk_session" >"$work/sdk-session.json"
  [[ "$(json_get .state <"$work/sdk-session.json")" == COMPLETED ]] || fail "the API says the SDK's session isn't completed"
  sdk_charged="$(json_get .chargedPaise <"$work/sdk-session.json")"
  [[ "$sdk_charged" == "$(printf '%s' "$sdk_out" | json_get .chargedPaise)" ]] || fail "the SDK's charge differs from the API's"
  ((sdk_charged > 0)) || fail "the SDK's session wasn't charged"
  [[ "$(printf '%s' "$sdk_out" | json_get .adapterBytes)" == "$(printf '%s' "$sdk_out" | json_get .listedBytes)" ]] ||
    fail "the SDK's downloaded adapter isn't the size the outputs list says"
  grep -q "saving the adapter" "$work/sdk.err" || fail "the SDK didn't follow the logs live"
  "$idleo" status "$sdk_session" | grep -q "completed" || fail "the CLI doesn't see the SDK's session as completed"
  note "python sdk: Client() signed in with the CLI's stored key; fine-tune-simulated run with a model folder, examples and the thoroughness preset, uploaded, booked, $(printf '%s' "$sdk_out" | json_get .toRunningSeconds) s from run() to running, logs followed live, waited to the end; charged ${sdk_charged} paise (the API's, equal); adapter $(printf '%s' "$sdk_out" | json_get .adapterBytes) bytes downloaded, equal to the outputs list's; problems raised as exceptions (not-found, unauthenticated, a usage error): PASS"

  # keys: this computer's key is listed; a second is made by device approval and revoked; the first signs out.
  "$idleo" keys | grep -q "CLI on e2e-laptop" || fail "idleo keys doesn't list this computer's key"
  "$idleo" keys create --name e2e-extra >"$work/extra.out" 2>&1 &
  local extra_pid=$!
  for _ in $(seq 1 30); do grep -q "check that the code is" "$work/extra.out" 2>/dev/null && break; sleep 0.5; done
  code="$(grep -o 'check that the code is [A-Z-]*' "$work/extra.out" | awk '{print $NF}')"
  login_id="$(curl -sf -H "Authorization: Bearer $cli_token" "$api/v1/cli-logins?code=$code" | json_get .id)"
  curl -sf -X POST -H "Authorization: Bearer $cli_token" "$api/v1/cli-logins/$login_id/approve" >/dev/null || fail "approving the extra key failed"
  wait "$extra_pid" || fail "idleo keys create failed: $(cat "$work/extra.out")"
  local extra_key extra_id
  extra_key="$(grep -o 'idleo_[a-z2-7]*' "$work/extra.out" | head -1)"
  [[ "$extra_key" =~ ^idleo_[a-z2-7]{52}$ ]] || fail "idleo keys create didn't print a key"
  [[ "$(IDLEO_API_KEY="$extra_key" "$idleo" --json whoami | json_get .email)" == "$user-cli@example.com" ]] || fail "the new key doesn't sign in"
  extra_id="$("$idleo" --json keys | python3 -c 'import json,sys; print([k["id"] for k in json.load(sys.stdin)["keys"] if k["name"] == "CLI on e2e-extra"][0])')"
  "$idleo" keys revoke "$extra_id" | grep -q "Revoked." || fail "idleo keys revoke didn't say so"
  local after=0
  IDLEO_API_KEY="$extra_key" "$idleo" whoami >/dev/null 2>&1 || after=$?
  [[ "$after" == 5 ]] || fail "a revoked key exited $after, not 5"
  local first_key
  first_key="$(python3 -c 'import json, sys; print(list(json.load(open(sys.argv[1])).values())[0]["key"])' "$config/credentials.json")"
  [[ "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $first_key" "$api/v1/me")" == 200 ]] || fail "the CLI's own key doesn't work before logout"
  "$idleo" logout | grep -q "Signed out" || fail "idleo logout didn't say so"
  after=0
  "$idleo" whoami >/dev/null 2>&1 || after=$?
  [[ "$after" == 5 ]] || fail "after logout, whoami exited $after, not 5"
  [[ "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $first_key" "$api/v1/me")" == 401 ]] || fail "logout left the key working on the server"
  [[ ! -e "$config/credentials.json" ]] || fail "logout left the key in the file"
  note "cli fine-tune: idleo run fine-tune-simulated with a 3-file model folder (12 MiB) and 40 examples, uploaded, booked, followed live to the end in ${run_seconds} s; requested to running ${to_running} s; charged ${charged} paise (the API's, equal); adapter ${adapter:-?} bytes downloaded, resumed from a part file and read (8 tensors): PASS"
  note "cli keys: this computer's key listed; a second made by device approval, working, revoked, then refused (exit 5); logout revoked the first and whoami exits 5: PASS"
  unset IDLEO_API_URL IDLEO_CONFIG_DIR IDLEO_NO_KEYCHAIN
}
scenario="cli"
: >"$out/summary-cli.txt"
scenario_cli
while IFS= read -r line; do summary+=("$line"); done <"$out/summary-cli.txt"

# The admin console in a browser (web/e2e/admin-journey.spec.ts, ADR 0027), last, so what it changes touches no other
# scenario. The script makes a new person an admin with grant-admin, enrols one more host and leaves it pending, and
# books a long run for the web journey's renter; the journey enrols a second factor and reaches the dashboard,
# activates the pending host, grants credit and finds it in the audit log, and force-stops the run once it has run
# past a minute. Then (ADR 0028) the run's renter rates it and reports a problem, the admin refunds part of the report
# (the platform's commission first, then the host's share), and the renter's credit and the host owner's earnings show
# it. Each action's one audit row is then read from the database, the stopped run is followed to its settlement, and
# the refund's ledger entries are read.
scenario_admin_web() {
  local admin_user="$user-admin" admin_token admin_id pending_json pending code n session report="$out/admin-journey.json"
  local rows charged running_since dispute
  admin_token="$(curl -sf -X POST "$sign_in/token" \
    -d grant_type=client_credentials -d "client_id=$admin_user" -d client_secret=unused -d scope=openid |
    json_field access_token)"
  [[ -n "$admin_token" ]] || fail "no token for the admin journey's person"
  # The spare code (index 7) belongs to the account journey; the admin takes the next one.
  invite_next=8
  activate_user "$admin_token"
  admin_id="$(token="$admin_token" api_get /v1/me | json_get .id)"
  ops grant-admin "$admin_user@example.com" --reason "'End-to-end admin journey'" ||
    fail "grant-admin failed; see private/e2e/ops.log"

  # One more host, enrolled and connected but never activated: the journey activates it.
  n=$((agents + 1))
  pending_json="$(api_post /v1/hosts -H "Idempotency-Key: \"$user-host-pending\"" -d '{"name":"End-to-end pending host"}')" ||
    fail "POST /v1/hosts failed for the pending host"
  pending="$(printf '%s' "$pending_json" | json_field id)"
  code="$(api_post "/v1/hosts/$pending/enrolment-code" | json_field code)"
  [[ -n "$pending" && -n "$code" ]] || fail "no pending host or enrolment code"
  rm -rf "$out/agent-$n"
  sed "s#agent-1#agent-$n#" "$out/agent-1.toml" >"$out/agent-$n.toml"
  "$agent" --config "$out/agent-$n.toml" enrol --code "$code" >>"$out/agent-$n.log" 2>&1 ||
    fail "enrolment of the pending host failed; see private/e2e/agent-$n.log"
  unset code
  run_agent "$n"
  local deadline=$((SECONDS + 30))
  until host_online "$pending" true; do
    ((SECONDS < deadline)) || fail "the pending host didn't come online within 30 s"
    sleep 0.5
  done
  list_host "$pending" 2
  [[ "$(api_get "/v1/hosts/$pending" | json_get .status)" == pending ]] || fail "the pending host isn't pending"

  session="$(token="$web_token" user="$web_user" book admin-stop long-run 10000)"
  [[ -n "$session" ]] || fail "booking the run the admin stops failed"
  token="$web_token" await_state "$session" RUNNING 90
  running_since="$(now_ms)"

  E2E_ADMIN_USER="$admin_user" E2E_PENDING_HOST="$pending" E2E_RUNNING_SESSION="$session" E2E_WEB_USER="$web_user" \
    E2E_HOST_OWNER="$user" E2E_RUNNING_SINCE="$running_since" E2E_ADMIN_REPORT="$report" SKIP_VISUAL=1 \
    web_test --project=admin-journey --reporter=line >>"$out/admin-journey.log" 2>&1 ||
    fail "the admin journeys failed; see private/e2e/admin-journey.log"
  note "admin journeys: $(grep -Eo '[0-9]+ passed' "$out/admin-journey.log" | tail -1) (a second factor enrolled from its QR code's secret and the dashboard reached, a pending host activated, ₹1.25 granted and found in the audit log, a running session force-stopped, rated and reported by its renter, and the report refunded): PASS"

  # Each action wrote exactly one audit row, by the admin, with the reason typed in the sheet.
  dispute="$(sql "select id from trust.disputes where session_id = '$session'")"
  [[ -n "$dispute" ]] || fail "the renter's report of $session isn't in trust.disputes"
  rows="$(sql "select string_agg(action || '=' || n, ' ' order by action) from (
      select action, count(*) as n from identity.audit_log
      where actor_user_id = '$admin_id'
        and ((action = 'admin.second_factor_confirm' and target_id = '$admin_id')
          or (action = 'dispute.refund' and target_id = '$dispute' and reason = 'end-to-end refund')
          or (action = 'host.activate' and target_id = '$pending' and reason = 'passed the end-to-end checks')
          or (action = 'credit.grant' and target_id = '$web_user_id' and reason = 'end-to-end admin grant'
              and (after->>'amountPaise')::bigint = 125)
          or (action = 'session.force_stop' and target_id = '$session' and reason = 'end-to-end force stop'))
      group by action) counted")"
  [[ "$rows" == "admin.second_factor_confirm=1 credit.grant=1 dispute.refund=1 host.activate=1 session.force_stop=1" ]] ||
    fail "the admin journey's audit rows aren't one per action: $rows"
  [[ "$(sql "select count(*) from identity.audit_log where action = 'admin.grant' and target_id = '$admin_id' and actor_name = 'e2e-script'")" == 1 ]] ||
    fail "grant-admin wrote no audit row"
  note "admin journey audit rows: grant-admin by the operator, then one row each by the admin ($rows), each with its reason: PASS"

  # The stopped run settles like any stop: COMPLETED, its minutes charged, its hold released.
  await_closed "$session" 120
  charged="$(token="$web_token" api_get "/v1/sessions/$session" | json_get .chargedPaise)"
  [[ "$(token="$web_token" api_get "/v1/sessions/$session" | json_get .state)" == COMPLETED ]] ||
    fail "the force-stopped session $session didn't complete"
  # Its charge is net of what the renter's report got back (ADR 0028), so with that it is its settlements' sum.
  [[ "$((charged + $(json_get .refund.refund <"$report")))" == "$(sql "select coalesce(sum(amount_paise), 0) from metering.settlements where session_id = '$session'")" ]] ||
    fail "the force-stopped session's charge $charged and its refund aren't its settlements' sum"
  note "admin force stop: session $session stopped for operator_stopped, completed and settled, its settlements' sum equal to the $charged paise charged and the $(json_get .refund.refund <"$report") refunded after its report: PASS"

  # The refund (ADR 0028): the report's own record, its one ledger transaction, and every number the pages showed.
  local paid refund commission refund_txn host_owner_id owner_token renter_token owner_earnings owner_ledger
  # The run's first tokens last an hour, and this is its end: new ones for the two people checked here.
  owner_token="$(curl -sf -X POST "$sign_in/token" \
    -d grant_type=client_credentials -d "client_id=$user" -d client_secret=unused -d scope=openid | json_field access_token)"
  renter_token="$(curl -sf -X POST "$sign_in/token" \
    -d grant_type=client_credentials -d "client_id=$web_user" -d client_secret=unused -d scope=openid | json_field access_token)"
  [[ -n "$owner_token" && -n "$renter_token" ]] || fail "no new tokens for the refund checks"
  paid="$(json_get .refund.paid <"$report")"
  commission="$(json_get .refund.fromCommission <"$report")"
  refund="$(json_get .refund.refund <"$report")"
  [[ "$(sql "select state || ':' || refund_paise || ':' || commission_refund_paise || ':' || host_refund_paise
             from trust.disputes where id = '$dispute'")" == "refunded:$refund:$commission:1" ]] ||
    fail "the report's refund isn't $refund paise, $commission from the commission and 1 from the host"
  refund_txn="$(sql "select refund_txn from trust.disputes where id = '$dispute'")"
  [[ "$(sql "select string_agg(account || ':' || direction || ':' || amount_paise, ',' order by account)
             from billing.ledger_entries where txn_id = '$refund_txn'")" == "host_payable:debit:1,platform_revenue:debit:$commission,renter_wallet:credit:$refund" ]] ||
    fail "the refund's ledger entries aren't one balanced transaction of $refund paise"
  [[ "$(token="$renter_token" api_get "/v1/sessions/$session" | json_get .chargedPaise)" == "$((paid - refund))" ]] ||
    fail "the refunded session's charge isn't $((paid - refund)) paise, net of the refund"
  [[ "$(sql "select count(*) from trust.ratings where session_id = '$session' and stars = 3 and comment = 'end-to-end rating comment'")" == 1 ]] ||
    fail "the renter's rating isn't kept"
  [[ "$(sql "select count(*) from billing.payout_holds where dispute_id = '$dispute' and released_at is not null")" == 1 ]] ||
    fail "the report's payout hold wasn't placed and ended"
  # The host's owner reads the refund as a line of their earnings, which still equal the ledger's host_payable entries.
  host_owner_id="$(token="$owner_token" api_get /v1/me | json_get .id)"
  owner_earnings="$(token="$owner_token" api_get /v1/earnings)" ||
    fail "GET /v1/earnings failed for the host's owner: $(curl -s -w ' HTTP %{http_code}' "$api/v1/earnings" -H "Authorization: Bearer $owner_token" | cut -c1-300)"
  # Read before the pipe below: a command inside it would take the pipe's input.
  owner_ledger="$(sql "select coalesce(sum(case e.direction when 'credit' then e.amount_paise else -e.amount_paise end), 0)
                       from billing.ledger_entries e join hosts.hosts h on h.id = e.owner_id
                       where e.account = 'host_payable' and h.owner_user_id = '$host_owner_id'")"
  printf '%s' "$owner_earnings" | python3 -c 'import json, sys
earnings = json.load(sys.stdin)
lines = [e for e in earnings["history"]["entries"] if e["kind"] == "refunded_to_renter"]
assert lines and lines[0]["amountPaise"] == -1 and lines[0]["label"] == "Refunded to renter" and not lines[0]["payoutHeld"], lines
assert earnings["totalPaise"] == int(sys.argv[1]), (earnings["totalPaise"], sys.argv[1])
assert sys.argv[2] not in json.dumps(earnings) and sys.argv[3] not in json.dumps(earnings), earnings' \
    "$owner_ledger" "$web_user" "$web_user_id" ||
    fail "the host owner's earnings don't show the refund, or don't equal the ledger"
  note "ratings and reports: session $session rated 3 stars with a comment and reported by its renter; the admin refunded $refund paise of $paid: $commission from the platform's commission, 1 from the host (one balanced transaction, charge now $((paid - refund))); the renter's credit and history, and the host owner's earnings (\"Refunded to renter\", -1 paise, equal to the ledger), show it: PASS"

  kill -TERM "$(cat "$out/agent-$n.pid")" 2>/dev/null
  wait "$(cat "$out/agent-$n.pid")" 2>/dev/null || true
  rm -f "$out/agent-$n.pid"
}
scenario="admin"
: >"$out/summary-admin.txt"
scenario_admin_web
while IFS= read -r line; do summary+=("$line"); done <"$out/summary-admin.txt"
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
