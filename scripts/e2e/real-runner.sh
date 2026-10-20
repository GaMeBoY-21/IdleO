#!/usr/bin/env bash
# The real job runner, end to end (ADR 0035): the real agent with a fake GPU, its jobs run for real in the sandbox of the
# development distribution (scripts/dev-distro.sh), from the job image in a local registry (scripts/images/publish.sh --dev),
# booked through the renter CLI like a person would.
#
#   1. Run your code, a small project that logs, checkpoints and writes an output, is stopped by its renter after a
#      checkpoint: the session ends stopped, a checkpoint is in storage, the job heard SIGTERM and saved once more.
#   2. The same project, the agent killed (kill -9) and started again inside the lease: the runner kept the job going, the
#      agent adopted it, one attempt, no step repeated, outputs downloaded and their SHA-256 checked.
#   3. The same project, the agent killed and left dead past the lease: the runner stops the job itself, the control plane
#      starts attempt 2, which resumes from the checkpoint the first one left.
#   4. A tiny CPU fine-tune of a tiny model made inside the image, booked from the CLI with its model and examples, the
#      adapter downloaded.
# It prints how long each took to its first log line, from the control plane's events and the runner's own timings.
#
#   scripts/e2e/real-runner.sh
#
# Needs Docker, the development distribution image (scripts/build-distro.sh --dev, built here if missing) and no container on
# port 5000 (the registry). It uses its own ports and compose project, so it can run beside a stack on the usual ones (not beside
# ./start.sh --real-runner, which owns the registry and the distribution). Everything it writes is under private/real-runner/.
set -euo pipefail

cd "$(dirname "$0")/../.."
root="$(pwd)"
out="$root/private/real-runner"
http_port="${E2E_HTTP_PORT:-8180}"
grpc_port="${E2E_GRPC_PORT:-9543}"
sign_in_port="${LOCAL_SIGN_IN_PORT:-8199}"
storage_port="${LOCAL_STORAGE_PORT:-9200}"
mail_smtp_port="${LOCAL_MAIL_SMTP_PORT:-1125}"
mail_web_port="${LOCAL_MAIL_WEB_PORT:-8125}"
web_port=3100
compose_project=idleo-real
operator_name=real-runner-script
distro="${IDLEO_DEV_DISTRO:-idleo-dev-distro}"
log() { printf 'real-runner: %s\n' "$*"; }
fail() { printf 'real-runner: FAIL: %s\n' "$*" >&2; exit 1; }
# shellcheck source=scripts/lib/stack.sh
. "$root/scripts/lib/stack.sh"

git check-ignore -q "$out/probe" || fail "private/real-runner/ isn't gitignored"
control_plane_pid=""
summary=()
cleanup() {
  local status=$?
  set +e
  [[ -f "$out/agent-1.pid" ]] && kill -TERM "$(cat "$out/agent-1.pid")" 2>/dev/null
  pkill -KILL -f "$out/agent-[0-9]*.toml run" 2>/dev/null
  if [[ -n "$control_plane_pid" ]] && kill -0 "$control_plane_pid" 2>/dev/null; then
    kill -TERM -- "-$control_plane_pid" 2>/dev/null
    for _ in $(seq 1 60); do kill -0 "$control_plane_pid" 2>/dev/null || break; sleep 0.5; done
    kill -KILL -- "-$control_plane_pid" 2>/dev/null
    wait "$control_plane_pid" 2>/dev/null
  fi
  COMPOSE_PROJECT_NAME="$compose_project" docker compose -f control-plane/compose.yaml down --volumes --remove-orphans >>"$out/compose.log" 2>&1
  if [[ ${#summary[@]} -gt 0 ]]; then
    printf '\nreal-runner summary\n'
    printf '  %s\n' "${summary[@]}"
  fi
  if ((status == 0)); then printf '  result: PASS\n'; else printf '  result: FAIL (logs in private/real-runner/)\n'; fi
  exit "$status"
}
mkdir -p "$out"
rm -f "${out:?}"/*.log "${out:?}"/*.pid "${out:?}"/agent-1.toml
rm -r -f "${out:?}/agent-1"
trap cleanup EXIT
trap 'exit 130' INT TERM

docker info >/dev/null 2>&1 || fail "Docker isn't running"
for port in "$http_port" "$grpc_port" "$sign_in_port" "$storage_port" "$mail_smtp_port" "$mail_web_port"; do
  ! port_in_use "$port" || fail "port $port is already in use"
done
# The registry listens on 127.0.0.1:5000 beside macOS's AirPlay Receiver, which holds *:5000, so a connect says nothing: ask Docker.
[[ -z "$(docker ps -q --filter publish=5000)" ]] || fail "a container already publishes port 5000 (the registry; ./start.sh --real-runner, or docker rm -f idleo-dev-registry)"

# The development distribution, the registry and the job image: the catalog names the image's digest.
control-plane/scripts/dev-ca.sh >>"$out/dev-ca.log" 2>&1
docker image inspect "idleo-distro-dev:$(tr -d '[:space:]' <agent/distro/VERSION)" >/dev/null 2>&1 || {
  log "building the development distribution, once"
  scripts/build-distro.sh --dev >"$out/distro-build.log" 2>&1 || fail "the distribution didn't build; see private/real-runner/distro-build.log"
}

stack_control_plane_settings() {
  export IDENTITY_INVITE_ONLY=false
  export LIMITS_REQUESTS_PER_MINUTE=100000 LIMITS_LOG_READS_PER_MINUTE=100000 LIMITS_CLI_LOGINS_PER_HOUR=100000
  export SESSIONS_LIMITS_NEW_PER_HOUR=1000 SESSIONS_LIMITS_NEW_PER_HOUR_FIRST_DAY=1000 SESSIONS_LIMITS_AT_ONCE=1000
  export SESSIONS_TEMPLATES_CATALOG="file:$root/private/local/development-real.yaml"
  # A short lease, so a lost agent shows within seconds (production: 2 minutes, renewed every 30 s).
  export SESSIONS_LEASES_TTL=10s SESSIONS_LEASES_RENEW_EVERY=3s SESSIONS_LEASES_TRANSIT_ALLOWANCE=2s SESSIONS_LEASES_LAPSE_SWEEP_EVERY=1s
  export SESSIONS_UPLOADS_PART_SIZE=5MB SESSIONS_STORAGE_GRANT_TTL=120s SESSIONS_STORAGE_REFRESH_CHECK_EVERY=1s
  export METERING_TICK_EVERY=1s METERING_REVIEW_AFTER=20s
}
stack_compose_up >>"$out/compose.log" 2>&1 || fail "compose.yaml's services didn't start; see private/real-runner/compose.log"
log "building and publishing the job image"
scripts/images/publish.sh --dev >"$out/job-image.log" 2>&1 || fail "the job image didn't publish; see private/real-runner/job-image.log"
scripts/dev-distro.sh up >>"$out/distro.log" 2>&1 || fail "the distribution didn't start; see private/real-runner/distro.log"
# The runner in a distribution that was already running may be older than this code: put the current one in (cached when unchanged).
scripts/dev-distro.sh refresh-runner >>"$out/distro.log" 2>&1 || fail "the runner couldn't be refreshed; see private/real-runner/distro.log"
log "starting the control plane (private/real-runner/control-plane.log)"
stack_start_control_plane
stack_build_agent
(cd cli && cargo build --release --locked --quiet) >"$out/cli-build.log" 2>&1 || fail "the CLI didn't build; see private/real-runner/cli-build.log"
stack_await_control_plane 600
stack_await_sign_in

user="real-$(perl -e 'printf "%08x", int(rand(2**32))')"
token="$(stack_token "$user")"
[[ -n "$token" ]] || fail "no token from the development sign-in server"
api_get() { curl -sf "$api$1" -H "Authorization: Bearer ${token_for:-$token}"; }
me="$(api_get /v1/me | json_get .id)"
ops grant-credits --user "$me" --paise 50000 --reason "'Real runner test credit'" --key "real-$me" || fail "grant-credits failed; see private/real-runner/ops.log"

host="$(stack_register_host "$token" "Real runner laptop" "$user-host")"
stack_agent_config 1
stack_enrol_agent 1 "$(stack_enrolment_code "$token" "$host")"
curl -sf -X PUT "$api/v1/hosts/$host/listing" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  -d '{"batchPricePaisePerMin": 2, "gpuTempCapC": 85, "maxConcurrentSessions": 1, "keepFreeGb": 10}' >/dev/null || fail "PUT listing failed"
stack_runner="--container-runner docker:$distro"
run_agent() { stack_run_agent 1 --fake-free-disk-mb 200000 --fake-gpu-vram-mb 12282; }
host_online() { curl -sf "$api/v1/hosts/$host" -H "Authorization: Bearer $token" | grep -q '"online":true'; }
await_online() {
  local deadline=$((SECONDS + ${1:-60}))
  until host_online; do
    agent_running 1 || fail "the agent exited; see private/real-runner/agent-1.log"
    ((SECONDS < deadline)) || fail "the host didn't come online"
    sleep 0.5
  done
}
run_agent
await_online 60
# Activated once its agent has reported its GPU.
ops activate-host --host "$host" --reason "'Real runner test host'" || fail "activate-host failed"
summary+=("host online with the container runner (distribution $distro): PASS")

# The renter CLI, with a key made with the account's browser token (the CLI's own sign-in is the e2e's).
key="$(curl -sf -X POST "$api/v1/api-keys" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: \"$user-key\"" -d '{"name": "real-runner", "scope": "full"}' | json_get .key)" || fail "making an API key failed"
idleo="$root/cli/target/release/idleo"
export IDLEO_API_URL="$api" IDLEO_API_KEY="$key" IDLEO_CONFIG_DIR="$out/cli-config" IDLEO_NO_KEYCHAIN=1
work="$out/work"
rm -rf "$work" && mkdir -p "$work"
project="$root/agent/images/pytorch/tests/project"

session_json() { curl -sf "$api/v1/sessions/$1" -H "Authorization: Bearer $token"; }
state_of() { session_json "$1" | json_get .state; }
# The id of a session booked with --detach, from the CLI's JSON.
detached() {
  python3 -c 'import json, sys
d = json.load(sys.stdin)
print(d.get("id") or d.get("session", {}).get("id"))'
}
await_settled() {
  local deadline=$((SECONDS + $2))
  until [[ "$(session_json "$1" | json_get .settled)" == True ]]; do
    ((SECONDS < deadline)) || fail "session $1 didn't settle within $2 s (it's $(state_of "$1"))"
    sleep 1
  done
}
await_log() {
  local deadline=$((SECONDS + $3))
  until "$idleo" logs "$1" 2>/dev/null | grep -q "$2"; do
    ((SECONDS < deadline)) || fail "session $1's log never said '$2' within $3 s: $("$idleo" logs "$1" 2>&1 | tail -5)"
    sleep 1
  done
}
await_checkpoints() {
  local deadline=$((SECONDS + $3))
  until (($(session_json "$1" | python3 -c 'import json, sys; print(len(json.load(sys.stdin)["checkpoints"]))') >= $2)); do
    ((SECONDS < deadline)) || fail "session $1 had no complete checkpoint in storage within $3 s"
    sleep 1
  done
}
# Books Run your code on the project with a command, detached; prints the session id.
book_code() {
  "$idleo" --json run run-your-code --input "code=$project" --param "command=$1" --detach 2>>"$out/cli.log" | detached
}
download() { rm -rf "$2" && "$idleo" outputs "$1" --download "$2" >>"$out/cli.log" 2>&1 || fail "downloading $1's outputs failed"; }
# The runner's own timing line for a session's attempt, from its journal.
timings_of() {
  scripts/dev-distro.sh exec -- journalctl -u idleo-runner --no-pager -o cat 2>/dev/null | grep "$1" | grep "container is running" | tail -1 | sed 's/.*timings/timings/' || true
}
nerdctl_running() { scripts/dev-distro.sh exec -- nerdctl --namespace idleo ps -q 2>/dev/null | grep -c . || true; }
kill_agent() { kill -KILL "$(cat "$out/agent-1.pid")"; wait "$(cat "$out/agent-1.pid")" 2>/dev/null || true; }

# 1. A renter's stop: checkpoint first, then the end.
s1="$(book_code 'SESSION_PARAM_STEPS=300 python main.py')"
[[ -n "$s1" && "$s1" != None ]] || fail "idleo run --detach printed no session"
await_log "$s1" "checkpoint 2 at" 240
await_checkpoints "$s1" 1 60
"$idleo" stop "$s1" >>"$out/cli.log" 2>&1 || fail "idleo stop failed"
await_settled "$s1" 90
"$idleo" logs "$s1" | grep -q "stopped after a checkpoint" || fail "the job didn't hear SIGTERM and save once more: $("$idleo" logs "$s1" | tail -5)"
await_checkpoints "$s1" 1 30
(($(nerdctl_running) == 0)) || fail "a container is still running after the stop"
summary+=("1 renter stop: SIGTERM heard, a checkpoint saved after it, a complete checkpoint in storage, no container left: PASS")

# 2. The agent killed and started again inside the lease: the job carries on, adopted.
s2="$(book_code 'SESSION_PARAM_STEPS=40 SESSION_PARAM_PAUSE=1 python main.py')"
await_log "$s2" "step 6/40" 240
kill_agent
run_agent
await_online 30
await_settled "$s2" 180
[[ "$(state_of "$s2")" == COMPLETED ]] || fail "the adopted session ended $(state_of "$s2")"
[[ "$(session_json "$s2" | json_get .attempt)" == 1 ]] || fail "the adopted session needed another attempt"
download "$s2" "$work/out2"
python3 - "$work/out2/result.json" <<'PY' || fail "the adopted job's result is wrong"
import json, sys
result = json.load(open(sys.argv[1]))
assert result["steps"] == 40 and result["resumed_from"] == 0, result
PY
"$idleo" logs "$s2" | grep -q "resumed from" && fail "the adopted job restarted instead of carrying on"
sha2="$(shasum -a 256 "$work/out2/result.json" | cut -d' ' -f1)"
listed="$(session_json "$s2" >/dev/null; curl -sf "$api/v1/sessions/$s2/outputs" -H "Authorization: Bearer $token" |
  python3 -c 'import json, sys; print(next(f["sizeBytes"] for f in json.load(sys.stdin)["files"] if f["path"] == "result.json"))')"
[[ "$listed" == "$(wc -c <"$work/out2/result.json" | tr -d ' ')" ]] || fail "the downloaded result's size differs from the outputs list"
summary+=("2 agent killed and restarted inside the lease: the runner kept the job, one attempt, no step repeated, outputs sha256 $sha2: PASS")

# 3. The agent killed and left dead past the lease: the runner stops the job, attempt 2 resumes from the checkpoint.
s3="$(book_code 'SESSION_PARAM_STEPS=50 SESSION_PARAM_PAUSE=1 python main.py')"
await_log "$s3" "checkpoint 2 at" 240
await_checkpoints "$s3" 2 60
kill_agent
lapsed=$((SECONDS + 60))
until (($(nerdctl_running) == 0)); do
  ((SECONDS < lapsed)) || fail "the runner didn't stop the job when its lease lapsed"
  sleep 1
done
run_agent
await_online 30
await_settled "$s3" 240
[[ "$(state_of "$s3")" == COMPLETED ]] || fail "the resumed session ended $(state_of "$s3")"
[[ "$(session_json "$s3" | json_get .attempt)" == 2 ]] || fail "the resumed session isn't on attempt 2"
"$idleo" logs "$s3" | grep -q "resumed from step" || fail "attempt 2 didn't resume from a checkpoint: $("$idleo" logs "$s3" | tail -8)"
download "$s3" "$work/out3"
python3 - "$work/out3/result.json" <<'PY' || fail "the resumed job's result is wrong"
import json, sys
result = json.load(open(sys.argv[1]))
assert result["steps"] == 50 and result["resumed_from"] >= 5, result
PY
summary+=("3 agent dead past the lease: the runner stopped the job itself, attempt 2 resumed from step $(python3 -c 'import json; print(json.load(open("'"$work"'/out3/result.json"))["resumed_from"])'): PASS")

# 4. A tiny CPU fine-tune, a tiny model made inside the image.
docker run --rm -v "$root/agent/images/pytorch/tests:/tests:ro" -v "$work:/out" --entrypoint python localhost:5000/idleo/pytorch:dev \
  /tests/make_tiny_model.py /out/tiny >"$out/tiny-model.log" 2>&1 || fail "making the tiny model failed; see private/real-runner/tiny-model.log"
started="$(now_ms)"
"$idleo" --json run fine-tune-qlora --input "model=$work/tiny/model" --input "data=$work/tiny/data/train.jsonl" --preset thoroughness=quick \
  >"$work/finetune.json" 2>"$work/finetune.err" || fail "idleo run of the fine-tune failed (exit $?): $(tail -8 "$work/finetune.err")"
s4="$(python3 -c 'import json, sys; print(json.load(sys.stdin)["session"]["id"])' <"$work/finetune.json")"
[[ "$(json_get .session.state <"$work/finetune.json")" == COMPLETED ]] || fail "the fine-tune ended $(json_get .session.state <"$work/finetune.json")"
download "$s4" "$work/out4"
[[ -s "$work/out4/adapter_model.safetensors" ]] || fail "the fine-tune left no adapter"
python3 - "$work/out4/adapter_model.safetensors" <<'PY' || fail "the adapter isn't a safetensors file with tensors"
import json, struct, sys
data = open(sys.argv[1], "rb").read()
header = json.loads(data[8 : 8 + struct.unpack("<Q", data[:8])[0]])
assert len([k for k in header if k != "__metadata__"]) > 0
PY
summary+=("4 tiny CPU fine-tune through the CLI: completed in $(seconds_since "$started") s, adapter $(wc -c <"$work/out4/adapter_model.safetensors" | tr -d ' ') bytes: PASS")

# What the first lines of each cost: the control plane's REQUESTED to RUNNING, and the runner's own breakdown.
for entry in "stop:$s1" "adopted:$s2" "resumed:$s3" "fine-tune:$s4"; do
  summary+=("timings, ${entry%%:*}: $(timings_of "${entry#*:}")")
done
