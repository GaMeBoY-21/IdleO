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
#   5. The 16 hostile tests (agent/runner/tests/hostile/laptop.py) through this same path: 13 PASS, 3 N/A, 0 FAIL.
#   6. An abuse stop and a clear (ADR 0038): a build listed in the development distribution's marker file runs in a session, the
#      runner stops it and the control plane suspends its renter and opens a review item; an admin (a real second-factor
#      session) sees it, clears it, and the renter is back and has their credit again.
#   7. Renters' own images (ADR 0042), through the real scanner container, the real control plane and the real runner:
#      images built byte by byte (scripts/e2e/make-image.py) and pushed to the development registry. One with a program the
#      blocklist names, deep in a lower layer (and deleted by the layer above), is blocked; a clean one whose layer unpacks past
#      the limit is blocked; a fake AWS key and a root USER warn; a moved tag changes nothing that runs; a new blocklist entry
#      blocks a cached image; a private registry with a token (never in the logs, the database, or the runner's environment);
#      and a clean image runs for real: downloaded (uncharged), then deleted from the host when the session ends. The hostile
#      tests run once more with the hostile job as a checked custom image.
# It prints how long each took to its first log line, from the control plane's events and the runner's own timings.
#
#   scripts/e2e/real-runner.sh
#
# Needs Docker, the development distribution image (scripts/build-distro.sh --dev, built here if missing) and no container on
# port 5000 (the registry). It uses its own ports and compose project, so it can run beside a stack on the usual ones (not beside
# ./start.sh --real-runner, which owns the registry and the distribution). Everything it writes is under private/real-runner/.
set -euo pipefail
. "$(dirname "$0")/../lib/quiet.sh"
quiet_entry real-runner "$@"
set -- ${QUIET_ARGS[@]+"${QUIET_ARGS[@]}"}

cd "$(dirname "$0")/../.."
root="$(pwd)"
out="$root/private/real-runner"
http_port="${E2E_HTTP_PORT:-8180}"
grpc_port="${E2E_GRPC_PORT:-9543}"
scanner_port="${E2E_SCANNER_PORT:-8195}"
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
  [[ -f "$out/auth-registry.pid" ]] && kill -TERM "$(cat "$out/auth-registry.pid")" 2>/dev/null
  [[ -f "$out/slow-registry.pid" ]] && kill -TERM "$(cat "$out/slow-registry.pid")" 2>/dev/null
  [[ -f "$out/agent-1.pid" ]] && kill -TERM "$(cat "$out/agent-1.pid")" 2>/dev/null
  pkill -KILL -f "$out/agent-[0-9]*.toml run" 2>/dev/null
  if [[ -n "$control_plane_pid" ]] && kill -0 "$control_plane_pid" 2>/dev/null; then
    kill -TERM -- "-$control_plane_pid" 2>/dev/null
    for _ in $(seq 1 60); do kill -0 "$control_plane_pid" 2>/dev/null || break; sleep 0.5; done
    kill -KILL -- "-$control_plane_pid" 2>/dev/null
    wait "$control_plane_pid" 2>/dev/null
  fi
  COMPOSE_PROJECT_NAME="$compose_project" docker compose -f control-plane/compose.yaml --profile scanner down --volumes --remove-orphans >>"$out/compose.log" 2>&1
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
for port in "$http_port" "$grpc_port" "$scanner_port" "$sign_in_port" "$storage_port" "$mail_smtp_port" "$mail_web_port"; do
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
  # The scanner's checks (ADR 0042): the stack's registry is a private address, so its names are allowed; a short claim and a
  # small unpack limit, so the tests of those finish in seconds.
  export IMAGES_CLAIM_LASTS=20s IMAGES_MAX_LAYER_UNPACKED=256MB IMAGES_SWEEP_EVERY=2s IMAGES_RECHECK_EVERY=3s IMAGES_PULL_TIME_LIMIT=8s
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
log "starting the image scanner (docker logs ${compose_project}-scanner-1)"
SCANNER_ALLOW_PRIVATE="registry.idleo.test,host.docker.internal" stack_start_scanner >>"$out/compose.log" 2>&1 || fail "the image scanner didn't start; see private/real-runner/compose.log"

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

# 5. The 16 hostile tests through the real path (ADR 0035, ADR 0038, agent/runner/tests/hostile/run.sh --host): the stack, the agent
#    and the runner in the distribution, as on a laptop. Thirteen must PASS and three (11, 15, 16) say N/A with their reasons; a
#    FAIL fails this.
started="$(now_ms)"
IDLEO_API_KEY="$key" IDLEO_API_URL="$api" python3 agent/runner/tests/hostile/laptop.py --host "$host" --cli "$idleo" \
  >"$work/hostile.txt" 2>"$out/hostile.err" || { cat "$work/hostile.txt" >&2; fail "the hostile tests through the real path failed; see private/real-runner/hostile.err"; }
cp "$work/hostile.txt" "$out/hostile-table.txt"
grep -q "16 rows, 13 PASS, 3 N/A, 0 FAIL" "$work/hostile.txt" || fail "the hostile tests' table isn't 13 PASS, 3 N/A: $(tail -3 "$work/hostile.txt")"
summary+=("5 the 16 hostile tests through the real path: 13 PASS, 3 N/A (11, 15 and 16, with their reasons), 0 FAIL, $(seconds_since "$started") s: PASS")

# 6. An abuse stop and a clear (ADR 0038). The development distribution's marker lists the SHA-256 of the hostile image's `sleep`
#    (a release distribution reads no such line); a second renter runs a copy of it, so the runner finds a listed build, stops the
#    session, and the control plane suspends that renter and opens a review item. An admin (a real second-factor session) clears it.
started="$(now_ms)"
hostile_image="$(sed -n 's/^ *image: \(registry.idleo.test:5000\/idleo\/hostile@sha256:[0-9a-f]\{64\}\).*/\1/p' private/local/development-real.yaml | head -1)"
[[ -n "$hostile_image" ]] || fail "the hostile image isn't named in private/local/development-real.yaml"
sleep_hash="$(scripts/dev-distro.sh exec -- nerdctl --namespace idleo run --rm --pull never --network none --entrypoint sha256sum "$hostile_image" /bin/sleep |
  tr -s ' ' '\n' | grep -E '^[0-9a-f]{64}$' | head -1)"
[[ -n "$sleep_hash" ]] || fail "couldn't hash the hostile image's sleep inside the distribution"
scripts/dev-distro.sh exec -- sh -c "grep -v '^abuse-' /etc/idleo/development >/tmp/marker; echo abuse-sha256=$sleep_hash >>/tmp/marker; cp /tmp/marker /etc/idleo/development" ||
  fail "couldn't list the test build in the distribution's marker file"
renter2="real-b-$(perl -e 'printf "%08x", int(rand(2**32))')"
token2="$(stack_token "$renter2")"
me2="$(curl -sf "$api/v1/me" -H "Authorization: Bearer $token2" | json_get .id)"
ops grant-credits --user "$me2" --paise 50000 --reason "'Real runner abuse test credit'" --key "real-$me2" || fail "grant-credits failed for the second renter"
key2="$(curl -sf -X POST "$api/v1/api-keys" -H "Authorization: Bearer $token2" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: \"$renter2-key\"" -d '{"name": "real-runner-b", "scope": "full"}' | json_get .key)" || fail "making the second renter's key failed"
# The admin: a user given the role by the operator command, a second factor enrolled from the secret the server shows.
admin="real-admin-$(perl -e 'printf "%08x", int(rand(2**32))')"
admin_token="$(stack_token "$admin")"
ops grant-admin "$admin@example.com" --reason "'Real runner abuse test'" || fail "grant-admin failed"
admin_session="$(python3 scripts/e2e/admin-session.py "$api" "$admin_token")" || fail "the admin's second factor didn't enrol"
admin_get() { curl -sf "$api$1" -H "Authorization: Bearer $admin_token" -H "Admin-Session: $admin_session"; }
s6="$(IDLEO_API_KEY="$key2" "$idleo" --json run hostile-test --param attack=run_named --max-spend 5 --detach 2>>"$out/cli.log" | detached)"
[[ -n "$s6" && "$s6" != None ]] || fail "booking the listed build failed"
# A stopped renter is suspended at once and can't read their sessions (a held account reaches only its data, deletion and
# support), so the admin watches the session instead.
deadline=$((SECONDS + 120))
until [[ "$(admin_get "/admin/v1/sessions/$s6" | json_get .settled)" == True ]]; do
  ((SECONDS < deadline)) || fail "the session running a listed build didn't end within 120 s"
  sleep 1
done
ending="$(admin_get "/admin/v1/sessions/$s6" | json_get .session.errorCode)"
[[ "$ending" == abuse_stopped ]] || fail "the listed build's session ended $ending, not abuse_stopped"
[[ "$(curl -sf "$api/v1/me" -H "Authorization: Bearer $token2" | json_get .status)" == suspended ]] || fail "the renter of a stopped session wasn't suspended"
refused="$(curl -s -o /dev/null -w '%{http_code}' "$api/v1/sessions" -H "Authorization: Bearer $token2")"
[[ "$refused" == 403 ]] || fail "a suspended renter's call answered $refused, not 403"
item="$(admin_get '/admin/v1/abuse?state=open' | python3 -c 'import json, sys
for item in json.load(sys.stdin)["items"]:
    if item["sessionId"] == sys.argv[1]:
        print(item["id"], item["action"], ",".join(item["kinds"]))' "$s6")"
[[ -n "$item" ]] || fail "no open review item for the stopped session"
read -r item_id item_action item_kinds <<<"$item"
[[ "$item_action" == suspended && "$item_kinds" == process_hash ]] || fail "the review item says $item_action for $item_kinds, not suspended for process_hash"
paid="$(admin_get "/admin/v1/abuse/$item_id" | json_get .paidPaise)"
cleared="$(curl -s -o "$work/clear.json" -w '%{http_code}' -X POST "$api/admin/v1/abuse/$item_id/clear" -H "Authorization: Bearer $admin_token" \
  -H "Admin-Session: $admin_session" -H 'Content-Type: application/json' -d '{"reason": "Real runner test: a copy of sleep, not a miner"}')"
[[ "$cleared" == 200 ]] || fail "clearing the review item answered $cleared: $(cat "$work/clear.json")"
[[ "$(curl -sf "$api/v1/me" -H "Authorization: Bearer $token2" | json_get .status)" == active ]] || fail "clearing didn't reinstate the renter"
credit="$(curl -sf "$api/v1/wallet" -H "Authorization: Bearer $token2" | json_get .availablePaise)"
[[ "$credit" == 50000 ]] || fail "after clearing the renter has $credit paise, not their 50000 back (the session cost $paid)"
[[ "$(admin_get "/admin/v1/abuse/$item_id" | json_get .item.state)" == cleared ]] || fail "the item isn't cleared"
summary+=("6 abuse: a listed build stopped (abuse_stopped), its renter suspended and refused (403), an open item for process_hash, an admin cleared it with a second factor, the renter is active again with all 50000 paise back (the session had cost $paid), $(seconds_since "$started") s: PASS")

# What the first lines of each cost: the control plane's REQUESTED to RUNNING, and the runner's own breakdown.
for entry in "stop:$s1" "adopted:$s2" "resumed:$s3" "fine-tune:$s4"; do
  summary+=("timings, ${entry%%:*}: $(timings_of "${entry#*:}")")
done

# 7. Renters' own images (ADR 0042), through the real scanner, control plane and runner. The renter has a verified PAN (the fake
#    PAN check, ADR 0040); the images are built byte by byte and pushed to the development registry, under the logical name the
#    scanner (on its own network), the control plane and the runner (through its mapping) all reach it by.
started="$(now_ms)"
registry_port="${LOCAL_REGISTRY_PORT:-5000}"
img_host="registry.idleo.test:5000"
make_image() { python3 -I scripts/e2e/make-image.py "127.0.0.1:$registry_port" "$@" --arch arm64; }
img_api() { curl -sf "$api$1" -H "Authorization: Bearer $token"; }
verify_pan() {
  curl -sf -X PUT "$api/v1/payout-details/pan" -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -d "{\"pan\":\"ABCPE$((1000 + RANDOM % 8000))F\",\"name\":\"Asha Rao\"}" | json_get .state
}
[[ "$(verify_pan "$token")" == verified ]] || fail "the renter's PAN wasn't verified by the fake check"
# Submits a reference, optionally as a private one ($2 user, $3 token); prints the image's id.
submit_image() {
  local body="{\"reference\":\"$1\"}"
  [[ -z "${2:-}" ]] || body="{\"reference\":\"$1\",\"registryUsername\":\"$2\",\"registryToken\":\"$3\"}"
  local answer
  answer="$(curl -s -X POST "$api/v1/images" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: \"img-$RANDOM$RANDOM\"" -d "$body")"
  json_get .id <<<"$answer" || fail "submitting $1 was refused: ${answer:0:300}"
}
image_json() { img_api "/v1/images/$1"; }
await_image() {
  local deadline=$((SECONDS + ${2:-180})) state
  until state="$(image_json "$1" | json_get .state)"; [[ "$state" != queued && "$state" != checking ]]; do
    ((SECONDS < deadline)) || fail "image $1's check didn't end within ${2:-180} s (it's $state): $(docker logs --tail 5 "${compose_project}-scanner-1" 2>&1 | tail -3)"
    sleep 1
  done
  printf '%s' "$state"
}
# The booking of a custom image with a command; prints the session id.
# With IMAGE_MAX_PRICE (rupees an hour) set, the booking is made even when no host is free to take it right now.
book_image() {
  "$idleo" --json run custom-image --image "$1" --param "command=$2" --max-spend 30 ${IMAGE_MAX_PRICE:+--max-price "$IMAGE_MAX_PRICE"} --detach 2>>"$out/cli.log" | detached
}

# 7a. A program the blocklist names, deep in a lower layer and deleted by the layer above, blocks the image: every ELF file in
#     every layer is hashed, not only what the image runs. The marker file lists the hash of the image's own bytes (the
#     development distribution's list is how the runner would learn it; here the control plane reads the signed list's hashes, so
#     the test publishes one naming the program).
make_image test/listed v1 listed --marker "a build on the test blocklist" >"$work/listed.digest"
listed_hash="$(python3 -c 'import hashlib; print(hashlib.sha256(b"\x7fELF\x02\x01\x01\x00a build on the test blocklist").hexdigest())')"
# The signed list names that program (as an admin's confirmed review item would); the control plane reads the feed every 10 s.
publish_blocklist() {
  python3 - "$work/additions.json" "$@" <<'PY'
import json, sys
out, *hashes = sys.argv[1:]
json.dump([{"sha256": h, "name": "a test program", "kind": "miner", "item": "01a1765a-0000-7000-8000-%012x" % i} for i, h in enumerate(hashes)], open(out, "w"))
PY
  LOCAL_STORAGE_PORT="$storage_port" scripts/release/blocklist.sh --dev --additions "$work/additions.json" >>"$out/blocklist.log" 2>&1 || fail "publishing the blocklist failed; see private/real-runner/blocklist.log"
}
publish_blocklist "$listed_hash"
listed="$(submit_image "$img_host/test/listed:v1")"
state="$(await_image "$listed" 240)"
[[ "$state" == blocked ]] || fail "the image with a listed program in a lower layer ended $state, not blocked: $(image_json "$listed")"
[[ "$(image_json "$listed" | json_get .reason)" == blocklisted ]] || fail "the listed image's reason isn't blocklisted"
admin_images() { admin_get "/admin/v1/images/$1"; }
[[ "$(admin_images "$listed" | json_get .blockedFile)" == "/opt/tools/deep/inside/miner (layer 1)" ]] ||
  fail "an admin isn't told which file matched, in which layer: $(admin_images "$listed")"
[[ "$(image_json "$listed" | grep -c 'opt/tools')" == 0 ]] || fail "the renter's own view names the matched file"
summary+=("7a a program on the blocklist deep in the lowest layer (deleted by the layer above) blocks the image; the admin sees /opt/tools/deep/inside/miner, layer 1; the renter doesn't: PASS")

# 7b. Clean, but a layer unpacks past the limit (256 MB here; 40 GB in production): a few KiB pushed, 1 GiB unpacked.
make_image test/bomb v1 bomb --unpacked-gib 1 >"$work/bomb.digest"
bomb="$(submit_image "$img_host/test/bomb:v1")"
[[ "$(await_image "$bomb" 240)" == blocked && "$(image_json "$bomb" | json_get .reason)" == too_large_unpacked ]] ||
  fail "the image that unpacks past the limit wasn't blocked as too_large_unpacked: $(image_json "$bomb")"
make_image test/traversal v1 traversal >"$work/traversal.digest"
traversal="$(submit_image "$img_host/test/traversal:v1")"
[[ "$(await_image "$traversal" 240)" == blocked && "$(image_json "$traversal" | json_get .reason)" == unsafe_path ]] ||
  fail "the image with a path out of the image wasn't blocked as unsafe_path: $(image_json "$traversal")"
summary+=("7b a layer that unpacks to 1 GiB (a few KiB pushed) is stopped at the limit; a path climbing out of the image is refused: PASS")

# 7c. Warnings, never blocking: a fake AWS-style key in a file, and a root USER.
make_image test/secret v1 secret >"$work/secret.digest"
secret="$(submit_image "$img_host/test/secret:v1")"
[[ "$(await_image "$secret" 240)" == ready ]] || fail "the image with a secret in it didn't end ready: $(image_json "$secret")"
warnings="$(image_json "$secret" | python3 -c 'import json, sys; print("\n".join(w["message"] for w in json.load(sys.stdin)["result"]["warnings"]))')"
grep -q "Your image seems to contain an AWS key in /app/.env. Anyone with this image can read it." <<<"$warnings" || fail "no secret warning: $warnings"
grep -q "Your image runs as root" <<<"$warnings" || fail "no root USER warning: $warnings"
grep -q "AKIAQWERTYUIOPASDFGH" <(image_json "$secret"; admin_images "$secret") && fail "a secret's text is in an API answer"
summary+=("7c a fake AWS key in /app/.env and a root USER are warnings, the image is ready; the key's text is in no answer: PASS")

# 7d. Pinning: after a check, a new image pushed to the same tag changes nothing that runs. The image is a real one (built here:
#     the byte-built ones are only checked), so the session that follows can run. The session records the digest that was
#     checked, and the runner is told that digest, though the tag now names something else.
docker build -q -t "127.0.0.1:$registry_port/test/pinned:v1" scripts/e2e/own-image >/dev/null 2>"$out/own-image-build.log" || fail "the real test image didn't build; see private/real-runner/own-image-build.log"
docker push -q "127.0.0.1:$registry_port/test/pinned:v1" >/dev/null 2>>"$out/own-image-build.log" || fail "the real test image didn't push; see private/real-runner/own-image-build.log"
tag_digest() { curl -sI -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json' "http://127.0.0.1:$registry_port/v2/test/pinned/manifests/v1" | tr -d '\r' | sed -n 's/^[Dd]ocker-[Cc]ontent-[Dd]igest: //p'; }
tag_before="$(tag_digest)"
pinned="$(submit_image "$img_host/test/pinned:v1")"
[[ "$(await_image "$pinned" 240)" == ready ]] || fail "the real test image didn't end ready: $(image_json "$pinned")"
pinned_digest="$(image_json "$pinned" | json_get .result.digest)"
[[ "$pinned_digest" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "the check reported no digest: $(image_json "$pinned")"
make_image test/pinned v1 secret >/dev/null
[[ "$(tag_digest)" != "$tag_before" ]] || fail "pushing to the tag again didn't move it"
s7="$(book_image "$pinned" 'echo hello from my own image > "$SESSION_OUTPUT_DIR/hello.txt"')" || fail "booking the checked image failed: $(tail -2 "$out/cli.log")"
[[ -n "$s7" && "$s7" != None ]] || fail "booking the checked image gave no session"
[[ "$(session_json "$s7" | json_get .image)" == "$img_host/test/pinned@$pinned_digest" ]] ||
  fail "the session doesn't run the digest that was checked: $(session_json "$s7" | json_get .image)"
summary+=("7d the tag moved after the check ($tag_before to $(tag_digest)); the session still records, and is told to run, the digest that was checked ($pinned_digest): PASS")

# 7e. A new blocklist entry blocks a cached image (nothing is downloaded again), a new booking of it is refused in plain words, and
#     its owner is told. (Cancelling its sessions that haven't started is held by the control plane's integration tests: a first
#     session with no host free ends at once, so none can be kept waiting here.) The image has a program of its own, which no
#     list names yet; then the list that names the program is published.
make_image test/recheck v1 listed --marker "a build that is on no list yet" >"$work/recheck.digest"
recheck_hash="$(python3 -c 'import hashlib; print(hashlib.sha256(b"\x7fELF\x02\x01\x01\x00a build that is on no list yet").hexdigest())')"
recheck="$(submit_image "$img_host/test/recheck:v1")"
[[ "$(await_image "$recheck" 240)" == ready ]] || fail "the image about to be listed isn't ready first: $(image_json "$recheck")"
scans_before="$(docker logs "${compose_project}-scanner-1" 2>&1 | grep -c "a check ended")"
publish_blocklist "$listed_hash" "$recheck_hash"
deadline=$((SECONDS + 120))
until [[ "$(image_json "$recheck" | json_get .state)" == blocked ]]; do
  ((SECONDS < deadline)) || fail "a cached image wasn't blocked by the newer list within 120 s: $(image_json "$recheck")"
  sleep 2
done
[[ "$(image_json "$recheck" | json_get .reason)" == blocklisted ]] || fail "the re-checked image's reason isn't blocklisted"
[[ "$(docker logs "${compose_project}-scanner-1" 2>&1 | grep -c "a check ended")" == "$scans_before" ]] || fail "the image was downloaded and scanned again to be blocked"
if IMAGE_MAX_PRICE=10 book_image "$recheck" true >/dev/null 2>&1; then fail "a newly blocked image could still be booked"; fi
tail -1 "$out/cli.log" | grep -q '"problem":"image-blocked"' || fail "booking a blocked image wasn't an image-blocked refusal: $(tail -1 "$out/cli.log")"
await_email "$user@example.com" "was blocked" 60 >/dev/null || fail "the owner wasn't told their image was blocked"
summary+=("7e a newer blocklist entry blocked the cached image within seconds without scanning it again, a new booking is refused as image-blocked, and the owner was emailed: PASS")

# 7f. CUDA matching: an image that says it needs CUDA 13 is never offered to a host whose driver doesn't run it. The stack's fake
#     GPU reports driver 0.0, which runs no CUDA at all; the real rule (api/cuda-drivers.json) says so, the booking is refused with
#     no-gpu-free, and an image that names no CUDA runs on the same host (7h).
make_image test/cuda13 v1 cuda13 >"$work/cuda13.digest"
cuda13="$(submit_image "$img_host/test/cuda13:v1")"
[[ "$(await_image "$cuda13" 240)" == ready && "$(image_json "$cuda13" | json_get .result.cudaNeeded)" == 13.0 ]] ||
  fail "the CUDA 13 image isn't ready needing CUDA 13.0: $(image_json "$cuda13")"
if book_image "$cuda13" true >/dev/null 2>&1; then fail "an image needing CUDA 13 was booked on a host whose driver runs none"; fi
tail -1 "$out/cli.log" | grep -q '"problem":"no-gpu-free"' || fail "the CUDA mismatch wasn't a no-gpu-free refusal: $(tail -1 "$out/cli.log")"
summary+=("7f an image needing CUDA 13.0 is never offered to the host (driver 0.0): the booking is refused as no GPU free: PASS")

# 7g. A private registry with a read-only token. The registry (scripts/e2e/auth-registry.py) answers 401 without it; the scanner is
#     given it only to pull, and it is nowhere afterwards: not in the control plane's log, the scanner's log, the database in clear,
#     or the runner's environment.
private_port=5079
private_token="ghp_e2e_readonly_$(perl -e 'printf "%08x%08x", int(rand(2**32)), int(rand(2**32))')"
python3 -I scripts/e2e/auth-registry.py "$private_port" "127.0.0.1:$registry_port" renter "$private_token" >"$out/auth-registry.log" 2>&1 &
echo $! >"$out/auth-registry.pid"
until curl -s -o /dev/null "http://127.0.0.1:$private_port/v2/"; do sleep 0.3; done
make_image priv/job v1 clean >"$work/private.digest"
private_ref="host.docker.internal:$private_port/priv/job:v1"
nope="$(submit_image "$private_ref")"
[[ "$(await_image "$nope" 120)" == blocked ]] || fail "a private image without its token wasn't refused: $(image_json "$nope")"
image_json "$nope" | json_get .detail | grep -q "read-only token" || fail "the refusal doesn't say to give a read-only token"
wrong="$(submit_image "$private_ref" renter not-the-token)"
[[ "$(await_image "$wrong" 120)" == blocked ]] || fail "a private image with a wrong token wasn't refused"
private="$(submit_image "$private_ref" renter "$private_token")"
[[ "$(await_image "$private" 240)" == ready ]] || fail "the private image with its token didn't end ready: $(image_json "$private")"
leaks=()
docker logs "${compose_project}-scanner-1" 2>&1 | grep -q "$private_token" && leaks+=("the scanner's log")
grep -q "$private_token" "$out/control-plane.log" && leaks+=("the control plane's log")
grep -q "$private_token" "$out/auth-registry.log" && leaks+=("the registry's request log")
docker exec "${compose_project}-postgres-1" pg_dump -U "$(docker exec "${compose_project}-postgres-1" printenv POSTGRES_USER)" --schema=images --schema=agentgateway 2>/dev/null | grep -q "$private_token" && leaks+=("the database, in clear")
image_json "$private" | grep -q "$private_token" && leaks+=("the renter's own view")
((${#leaks[@]} == 0)) || fail "the registry token appeared in: ${leaks[*]}"
summary+=("7g a private registry: no token and a wrong token are refused in plain words, the right one is checked ready; the token is in no log, no row in clear, no API answer: PASS")

# 7h. A clean image runs for real, as a person would run it: booked from the CLI with a command, downloaded by the runner at the
#     attempt's start (shown as the session's own pull, never charged), run in the sandbox as 10001 in the image's own working
#     directory, its output downloaded; then the image is gone from the host: containerd's store holds no trace of it.
own_ref="$img_host/test/pinned@$pinned_digest"
stored() { scripts/dev-distro.sh exec -- nerdctl --namespace idleo images --format '{{.Name}}' 2>/dev/null | grep -cF "test/pinned" || true; }
# The session booked in 7d (the host was online) has run and ended by now: its image went with it.
await_settled "$s7" 300
[[ "$(state_of "$s7")" == COMPLETED ]] || fail "7d's session ended $(state_of "$s7"): $("$idleo" logs "$s7" | tail -5)"
[[ "$(stored)" == 0 ]] || fail "the renter's image is still in containerd's store after 7d's session ended"
s8="$(book_image "$pinned" 'id -u > "$SESSION_OUTPUT_DIR/uid.txt"; pwd > "$SESSION_OUTPUT_DIR/pwd.txt"; echo ran > "$SESSION_OUTPUT_DIR/ran.txt"')"
[[ -n "$s8" && "$s8" != None ]] || fail "booking the clean image failed: $(tail -3 "$out/cli.log")"
seen_download=false
deadline=$((SECONDS + 300))
until [[ "$(session_json "$s8" | json_get .settled)" == True ]]; do
  ((SECONDS < deadline)) || fail "the own-image session didn't settle within 300 s (it's $(state_of "$s8")): $("$idleo" logs "$s8" 2>&1 | tail -5)"
  [[ "$(session_json "$s8" | json_get .imageDownload)" != None ]] && seen_download=true
  sleep 0.5
done
[[ "$(state_of "$s8")" == COMPLETED ]] || fail "the own-image session ended $(state_of "$s8"): $("$idleo" logs "$s8" | tail -8)"
download "$s8" "$work/out8"
[[ "$(cat "$work/out8/uid.txt")" == 10001 ]] || fail "the renter's image ran as $(cat "$work/out8/uid.txt"), not 10001"
[[ "$(cat "$work/out8/pwd.txt")" == /app ]] || fail "the renter's image didn't run in its own working directory: $(cat "$work/out8/pwd.txt")"
[[ "$(session_json "$s8" | json_get .image)" == "$own_ref" ]] || fail "the session ran something other than the checked digest"
[[ "$(stored)" == 0 ]] || fail "the renter's image is still in containerd's store after the session ended: $(scripts/dev-distro.sh exec -- nerdctl --namespace idleo images 2>&1 | head -5)"
charged="$(session_json "$s8" | json_get .chargedPaise)"
ran_secs="$(session_json "$s8" | python3 -c 'import json, sys; from datetime import datetime as d; s = json.load(sys.stdin); print(int((d.fromisoformat(s["endedAt"].replace("Z", "+00:00")) - d.fromisoformat(s["startedAt"].replace("Z", "+00:00"))).total_seconds()))')"
# Billed from minute 0, which is when the job starts: the pull is before it. 1 paise/min × the minutes the job ran, ceilinged to whole minutes.
((charged <= (ran_secs / 60 + 1) * 2 * 2)) || fail "charged $charged paise for a job that ran $ran_secs s: the download looks charged"
"$idleo" logs "$s8" | grep -qi "pull\|download" && true
summary+=("7h a clean image ran for real: as 10001 in its own /app, digest $pinned_digest, outputs downloaded, image deleted from containerd afterwards (0 left), charged $charged paise for a job that ran $ran_secs s (download before minute 0, uncharged), download progress seen on the session: $seen_download: PASS")

# 7i. The pull time limit (8 s here; 20 minutes in production): an image served at 200 KB/s that needs longer fails the session with a
#     plain message, before minute 0, with no charge, and the half-pulled image is deleted from the host.
slow_port=5080
python3 -I scripts/e2e/auth-registry.py "$slow_port" "127.0.0.1:$registry_port" slow "$private_token" 200000 >"$out/slow-registry.log" 2>&1 &
echo $! >"$out/slow-registry.pid"
until curl -s -o /dev/null "http://127.0.0.1:$slow_port/v2/"; do sleep 0.3; done
python3 -I scripts/e2e/make-image.py "127.0.0.1:$registry_port" slow/job v1 heavy --wire-mb 12 --arch arm64 >/dev/null
slow_ref="host.docker.internal:$slow_port/slow/job:v1"
slow="$(submit_image "$slow_ref" slow "$private_token")"
[[ "$(await_image "$slow" 240)" == ready ]] || fail "the slow image wasn't checked: $(image_json "$slow")"
credit_slow_before="$(curl -sf "$api/v1/wallet" -H "Authorization: Bearer $token" | json_get .availablePaise)"
s9="$(book_image "$slow" true)" || fail "booking the slow image failed: $(tail -2 "$out/cli.log")"
await_settled "$s9" 120
[[ "$(state_of "$s9")" == FAILED && "$(session_json "$s9" | json_get .errorCode)" == image_pull_timed_out ]] ||
  fail "the session whose image downloaded too slowly ended $(state_of "$s9") / $(session_json "$s9" | json_get .errorCode), not image_pull_timed_out"
[[ "$(session_json "$s9" | json_get .chargedPaise)" == 0 ]] || fail "a session whose image never finished downloading was charged"
[[ "$(curl -sf "$api/v1/wallet" -H "Authorization: Bearer $token" | json_get .availablePaise)" == "$credit_slow_before" ]] || fail "the renter's credit moved for a pull that timed out"
[[ "$(scripts/dev-distro.sh exec -- nerdctl --namespace idleo images --format '{{.Name}}' | grep -cF 'slow/job' || true)" == 0 ]] || fail "the half-downloaded image was left on the host"
summary+=("7i an image downloaded at 200 KB/s past the 8 s limit failed the session as image_pull_timed_out before minute 0: charged 0, credit unmoved, nothing left on the host: PASS")

# 7j. The hostile job, once more, as a renter's own image (ADR 0042): the same image the hostile-test template runs, submitted
#     for a check like any other, booked as `custom-image` with the renter's command. The sandbox is the same whatever the image's
#     name: the table must read as scenario 5's, except the two attacks that need the hostile template's small memory and disk limits (11 PASS, 5 N/A, 0 FAIL). Running through this path is the point.
started_hostile="$(now_ms)"
hostile_ref="$img_host/idleo/hostile@${hostile_image##*@}"
hostile_check="$(submit_image "$hostile_ref")"
[[ "$(await_image "$hostile_check" 300)" == ready ]] || fail "the hostile image wasn't checked ready as a custom image: $(image_json "$hostile_check")"
IDLEO_API_KEY="$key" IDLEO_API_URL="$api" python3 agent/runner/tests/hostile/laptop.py --host "$host" --cli "$idleo" --image "$hostile_check" \
  >"$work/hostile-custom.txt" 2>"$out/hostile-custom.err" || { cat "$work/hostile-custom.txt" >&2; fail "the hostile tests as a custom image failed; see private/real-runner/hostile-custom.err"; }
cp "$work/hostile-custom.txt" "$out/hostile-custom-table.txt"
grep -q "16 rows, 11 PASS, 5 N/A, 0 FAIL" "$work/hostile-custom.txt" || fail "the hostile tests as a custom image aren't 11 PASS, 5 N/A: $(tail -3 "$work/hostile-custom.txt")"
summary+=("7j the 16 hostile tests with the hostile job as a custom image (checked, then booked with the renter's command): 11 PASS, 5 N/A (11, 15, 16 as before, and 08, 09, which need the hostile template's small limits: they stay in scenario 5), 0 FAIL, $(seconds_since "$started_hostile") s: PASS")
summary+=("7 renters' own images, all of 7a to 7j, $(seconds_since "$started") s: PASS")
