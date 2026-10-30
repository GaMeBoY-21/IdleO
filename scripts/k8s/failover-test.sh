#!/usr/bin/env bash
# The proof that losing one of anything loses nothing (ADR 0047; .github/workflows/failover.yml runs it weekly), on the
# Mac's cluster under Flux, as production runs: two control planes (Flagger's primary), CloudNativePG's primary and
# standby, one Valkey. Renters' reads and writes go through the Gateway the whole time, and two fake-GPU agents are
# connected, one running a booking across each loss.
#
#   1. a control-plane pod killed hard (--grace-period=0), the one holding both agents' streams: they reconnect to the
#      other within their backoff; a command queued meanwhile (a self-test of the idle host) is delivered; the running
#      booking runs to COMPLETED, billed once;
#   2. the database's primary pod killed hard: the standby is promoted, the control planes reconnect by themselves, and
#      the booking completes; the seconds writes were refused are counted, and the money check passes;
#   3. Valkey restarted: presence is unknown, never offline, until agents heartbeat; a booking made while it's down
#      waits, then matches and completes;
#   4. the control-plane pods evicted one at a time, as a drain does: the disruption budget refuses the second while
#      the first's replacement starts, and no renter's request fails;
#   5. a point-in-time restore drill (ops/backup/drill.sh): the RPO and the RTO.
#
# It prints what renters saw at each step: requests that failed, agents' time back online, the seconds writes were
# refused. Logs: private/k8s/logs/ (failover-test.log, agent-1.log, agent-2.log). It leaves the cluster running.
#
#   scripts/k8s/failover-test.sh            up.sh --gitops first (push the branch), then every step
#   scripts/k8s/failover-test.sh --no-up    on the cluster that's there (under Flux, or plain Helm's for quick work)
set -euo pipefail
. "$(dirname "$0")/../lib/quiet.sh"
quiet_entry k8s-failover-test "$@"
set -- ${QUIET_ARGS[@]+"${QUIET_ARGS[@]}"}
log() { printf 'failover-test: %s\n' "$*"; }
fail() { printf 'failover-test: FAIL: %s\n' "$*" >&2; exit 1; }
pass() { printf 'failover-test: PASS: %s\n' "$*"; summary+=("$*"); }
. "$(dirname "$0")/lib.sh"
cd "$k8s_root"
root="$k8s_root"
summary=()
out="$k8s_state/logs"
state="$k8s_state/failover-test"
mkdir -p "$out" "$state"
started_at=$SECONDS

if [[ "${1:-}" != --no-up ]]; then
  log "bringing the cluster up under Flux (up.sh --gitops)"
  scripts/k8s/up.sh --gitops || fail "up.sh --gitops failed; see private/k8s/logs/up.log"
fi
in_namespace() { kubectl -n "$namespace" "$@"; }

# The stack's shared steps (scripts/lib/stack.sh), pointed at the cluster.
api="$api_url"
sign_in="$sign_in_url"
grpc_port="$agents_port"
operator_name=k8s-failover-test
agent_ca_root="$k8s_state/agent-ca.pem"
# shellcheck source=scripts/lib/stack.sh
. "$root/scripts/lib/stack.sh"
export CURL_CA_BUNDLE="$local_ca/ca.pem"
ops() { OPERATOR="$operator_name" scripts/k8s/ops.sh "$@" >>"$out/failover-test-ops.log" 2>&1; }
now() { perl -MTime::HiRes=time -e 'printf "%.2f", time'; }
pids=()
cleanup() {
  local pid
  for pid in ${pids[@]+"${pids[@]}"}; do kill -TERM "$pid" 2>/dev/null || true; done
}
trap cleanup EXIT

control_plane_pods() {
  in_namespace get pods -l idleo.in/control-plane=true --field-selector=status.phase=Running \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}'
}
# The control-plane pod that holds a host's agent stream: its presence names the instance, and each pod logs its own.
pod_of_host() {
  local instance pod
  instance="$(in_namespace exec idleo-valkey-0 -- valkey-cli hget "agentgateway:presence:$1" instance_id 2>/dev/null)"
  [[ -n "$instance" ]] || return 1
  for pod in $(control_plane_pods); do
    # Read whole first: grep -q would end the pipe early, which pipefail counts as a failure.
    [[ "$(in_namespace logs "$pod" 2>/dev/null || true)" == *"Control-plane instance id $instance"* ]] && { echo "$pod"; return 0; }
  done
  return 1
}

# People: the renter who books, a reader and a writer for the traffic (each under the request limit on their own),
# and the hosts' owner.
person() {
  local token
  token="$(stack_token "$1")"
  [[ -n "$token" ]] || fail "no token for $1 from the development sign-in server"
  printf '%s' "$token"
}
run_id="$(openssl rand -hex 4)"
renter="$(person "failover-renter-$run_id")"
reader="$(person "failover-reader-$run_id")"
writer="$(person "failover-writer-$run_id")"
owner="$(person "failover-owner-$run_id")"
as() { local token="$1"; shift; curl -sf "$@" -H "Authorization: Bearer $token"; }
renter_id="$(as "$renter" "$api/v1/me" | json_get .id)"
ops grant-credits --user "$renter_id" --paise 100000 --reason "'Failover test'" --key "failover-$run_id" ||
  fail "grant-credits failed; see private/k8s/logs/failover-test-ops.log"

# Two hosts, each a fake-GPU agent of this tree.
log "building the agent"
stack_build_agent
agent="$root/agent/target/release/idleo-agent"
price=2
hosts=()
for n in 1 2; do
  host="$(stack_register_host "$owner" "Failover laptop $n" "failover-$run_id-host-$n")"
  rm -rf "$state/agent-$n"
  stack_agent_config "$n"
  stack_enrol_agent "$n" "$(stack_enrolment_code "$owner" "$host")"
  : >"$out/agent-$n.log"
  stack_run_agent "$n" --fake-gpu-model "RTX 4060 Laptop" --fake-gpu-vram-mb 8192 --fake-free-disk-mb 200000
  pids+=("$(cat "$state/agent-$n.pid")")
  hosts+=("$host")
done
host_state() { as "$owner" "$api/v1/hosts/$1" | json_get .presence; }
for host in "${hosts[@]}"; do
  deadline=$((SECONDS + 90))
  until [[ "$(host_state "$host")" == ONLINE ]]; do
    ((SECONDS < deadline)) || fail "host $host didn't come online; see private/k8s/logs/agent-*.log"
    sleep 2
  done
  curl -sf -X PUT "$api/v1/hosts/$host/listing" -H "Authorization: Bearer $owner" -H 'Content-Type: application/json' \
    -d "{\"batchPricePaisePerMin\": $price, \"gpuTempCapC\": 85, \"maxConcurrentSessions\": 1, \"keepFreeGb\": 10}" \
    >/dev/null || fail "host $host's listing couldn't be set"
  ops activate-host --host "$host" --reason "'Failover test'" || fail "activate-host failed"
done
pass "two fake-GPU agents online and listed; a renter with credit; reads and writes going through the Gateway"

# Renters' traffic through the Gateway the whole time: a read (the wallet) and a write (notification choices, replaced
# with the same) each about every 1.2 s, each person under the 60 a minute an address or person may make. Each line:
# the time, read or write, the status.
traffic="$state/traffic"
: >"$traffic"
for kind in read write; do
  (
    while true; do
      if [[ "$kind" == read ]]; then
        code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$api/v1/wallet" -H "Authorization: Bearer $reader" || true)"
      else
        code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 -X PUT "$api/v1/me/notifications" \
          -H "Authorization: Bearer $writer" -H 'Content-Type: application/json' -d '{"items": []}' || true)"
      fi
      printf '%s %s %s\n' "$(now)" "$kind" "${code:-000}" >>"$traffic"
      sleep 1.2
    done
  ) &
  pids+=($!)
done
sleep 5
grep -q ' write 200$' "$traffic" || fail "the writer's requests don't succeed even before anything is lost: $(tail -3 "$traffic")"
# What renters saw between two moments: "<failed> of <all>" reads, and the same for writes.
seen() {
  awk -v from="$1" -v to="$2" -v kind="$3" '$1 >= from && $1 <= to && $2 == kind { all++; if ($3 !~ /^2/) failed++ }
    END { printf "%d of %d", failed, all }' "$traffic"
}
# The span writes failed between two moments: from the first failed write to the first success after the last one.
refused_writes() {
  awk -v from="$1" -v to="$2" '$1 >= from && $1 <= to && $2 == "write" {
      if ($3 !~ /^2/) { if (!first) first = $1; last = $1; pending = 1 } else if (pending) { back = $1; pending = 0 } }
    END { if (!first) print "0.0"; else printf "%.1f", (back ? back : last) - first }' "$traffic"
}

# Until one of the hosts could take a session: online, nothing running, no self-test running.
await_free_host() {
  local deadline=$((SECONDS + 300)) host
  while ((SECONDS < deadline)); do
    for host in "${hosts[@]}"; do
      [[ "$(as "$owner" "$api/v1/hosts/$host" | json_get .currentSession)" == None &&
        "$(as "$owner" "$api/v1/hosts/$host/self-test" | json_get .run.state 2>/dev/null)" != running ]] && return 0
    done
    sleep 3
  done
  fail "neither host was free to book within 300 s"
}
book() {
  await_free_host
  as "$renter" "$api/v1/sessions" -X POST -H 'Content-Type: application/json' -H "Idempotency-Key: \"$1\"" \
    -d "{\"templateId\": \"$2\", \"maxPricePaisePerMin\": 5, \"maxSpendPaise\": 5000}" | json_get .id
}
session_field() { as "$renter" "$api/v1/sessions/$1" | json_get ".$2"; }
await_session() {
  local deadline=$((SECONDS + $3)) now_state
  until now_state="$(session_field "$1" state)" && [[ "$now_state" =~ ^($2)$ ]]; do
    [[ "$now_state" =~ ^(FAILED|NO_HOST_FREE|CANCELLED)$ ]] && fail "session $1 ended $now_state"
    ((SECONDS < deadline)) || fail "session $1 wasn't $2 within $3 s (it's $now_state)"
    sleep 2
  done
}
# A charged-run (150 s, three started minutes) runs to the end, settled, billed once: three minutes at the price.
await_billed_once() {
  local deadline=$((SECONDS + 300))
  await_session "$1" COMPLETED 400
  until [[ "$(session_field "$1" settled)" == True ]]; do
    ((SECONDS < deadline)) || fail "session $1 didn't settle"
    sleep 2
  done
  charged="$(session_field "$1" chargedPaise)"
  ((charged == 3 * price)) || fail "session $1 was charged $charged paise, not 3 minutes at $price"
}
# When an agent was back after $2 (epoch seconds): its first "connected" line after it, from its own log.
back_after() {
  python3 - "$out/agent-$1.log" "$2" <<'PY'
import sys
from datetime import datetime
path, since = sys.argv[1], float(sys.argv[2])
for line in open(path, errors="replace"):
    try:
        at = datetime.fromisoformat(line.split()[0].replace("Z", "+00:00")).timestamp()
    except (ValueError, IndexError):
        continue
    if at > since and " connected host_id=" in line:
        print("%.1f" % (at - since))
        break
PY
}

# 1. A control-plane pod killed hard: the one holding the busy host's stream, with the idle host's moved onto it too.
session="$(book "failover-$run_id-1" charged-run)" || fail "booking the first session failed"
await_session "$session" RUNNING 120
busy="" idle=""
running_on="$(session_field "$session" hostId)"
for i in 0 1; do
  if [[ "${hosts[$i]}" == "$running_on" ]]; then busy=$((i + 1)); else idle=$((i + 1)); fi
done
[[ -n "$busy" && -n "$idle" ]] || fail "couldn't tell which host runs the session"
victim="$(pod_of_host "${hosts[$((busy - 1))]}")" || fail "no control-plane pod holds the busy host's stream"
for try in $(seq 1 12); do
  [[ "$(pod_of_host "${hosts[$((idle - 1))]}" || true)" == "$victim" ]] && break
  # Its agent again, until the load balancer puts it on the same pod.
  kill -TERM "$(cat "$state/agent-$idle.pid")" 2>/dev/null || true
  sleep 2
  stack_run_agent "$idle" --fake-gpu-model "RTX 4060 Laptop" --fake-gpu-vram-mb 8192 --fake-free-disk-mb 200000
  pids+=("$(cat "$state/agent-$idle.pid")")
  sleep 4
done
[[ "$(pod_of_host "${hosts[$((idle - 1))]}" || true)" == "$victim" ]] || fail "couldn't get both agents onto one pod"
killed="$(now)"
in_namespace delete pod "$victim" --grace-period=0 --force >/dev/null 2>&1
# Queued while the idle host's agent reconnects: its presence still names the dead pod, so no hint reaches it.
# Asked again, as a person would, if the Gateway's first try meets the dead pod.
for try in 1 2 3 4 5; do
  asked="$(curl -s -w ' %{http_code}' -X POST "$api/v1/hosts/${hosts[$((idle - 1))]}/self-tests" \
    -H "Authorization: Bearer $owner" -H "Idempotency-Key: \"failover-$run_id-self-test\"")"
  [[ "$asked" == *" 202" ]] && break
  sleep 1
done
[[ "$asked" == *" 202" ]] || fail "the idle host's self-test couldn't be asked for: $asked"
deadline=$((SECONDS + 120))
until [[ "$(as "$owner" "$api/v1/hosts/${hosts[$((idle - 1))]}/self-test" | json_get .run.state)" =~ ^(passed|failed)$ ]]; do
  ((SECONDS < deadline)) || fail "the self-test queued during the outage never reached the agent (it's $(as "$owner" "$api/v1/hosts/${hosts[$((idle - 1))]}/self-test" | json_get .run.state))"
  sleep 2
done
back_busy="$(back_after "$busy" "$killed")"
back_idle="$(back_after "$idle" "$killed")"
[[ -n "$back_busy" && -n "$back_idle" ]] || fail "an agent didn't reconnect after the kill; see private/k8s/logs/agent-*.log"
await_billed_once "$session"
done_at="$(now)"
pass "a control-plane pod killed hard: the agents were back in $back_busy s and $back_idle s; the self-test queued meanwhile was delivered; the running booking COMPLETED, billed once ($charged paise); renters' reads failed: $(seen "$killed" "$done_at" read), writes: $(seen "$killed" "$done_at" write)"

# 2. The database's primary pod killed hard.
session="$(book "failover-$run_id-2" charged-run)" || fail "booking the second session failed"
await_session "$session" RUNNING 120
old_primary="$(in_namespace get cluster idleo-database -o jsonpath='{.status.currentPrimary}')"
killed="$(now)"
in_namespace delete pod "$old_primary" --grace-period=0 --force >/dev/null 2>&1
deadline=$((SECONDS + 180))
until new_primary="$(in_namespace get cluster idleo-database -o jsonpath='{.status.currentPrimary}')" &&
  [[ -n "$new_primary" && "$new_primary" != "$old_primary" ]] &&
  [[ "$(in_namespace get pod "$new_primary" -o jsonpath='{.status.containerStatuses[?(@.name=="postgres")].ready}')" == true ]]; do
  ((SECONDS < deadline)) || fail "the standby wasn't promoted within 180 s"
  sleep 0.5
done
promoted="$(perl -e 'printf "%.1f", $ARGV[0] - $ARGV[1]' "$(now)" "$killed")"
await_billed_once "$session"
done_at="$(now)"
refused="$(refused_writes "$killed" "$done_at")"
# The old primary comes back as the new one's standby (CloudNativePG rebuilds it).
deadline=$((SECONDS + 300))
until [[ "$(in_namespace get cluster idleo-database -o jsonpath='{.status.readyInstances}')" == 2 ]]; do
  ((SECONDS < deadline)) || fail "the database didn't get its standby back within 300 s"
  sleep 3
done
ops/backup/verify-ledger.sh --cluster idleo-database >"$state/verify-ledger.log" ||
  fail "the money check failed after the failover: $(cat "$state/verify-ledger.log")"
pass "the database's primary killed hard: $new_primary promoted in $promoted s; writes refused for $refused s; renters' reads failed: $(seen "$killed" "$done_at" read), writes: $(seen "$killed" "$done_at" write); the booking COMPLETED, billed once; $old_primary is the standby again; the money check passes"

# 3. Valkey restarted. The owner watches the idle host's presence throughout; a renter books while Valkey is gone.
states="$state/presence"
: >"$states"
(
  while true; do
    printf '%s %s\n' "$(now)" "$(as "$owner" "$api/v1/hosts/${hosts[0]}" | json_get .presence || echo ERROR)" >>"$states"
    sleep 1.2
  done
) &
watcher=$!
pids+=("$watcher")
sleep 3
restarted="$(now)"
in_namespace delete pod idleo-valkey-0 >/dev/null
session="$(book "failover-$run_id-3" cuda-smoke-test)" || fail "booking while Valkey restarts failed"
await_session "$session" COMPLETED 300
deadline=$((SECONDS + 120))
until [[ "$(tail -1 "$states" | cut -d' ' -f2)" == ONLINE ]] &&
  awk -v from="$restarted" '$1 > from && $2 != "ONLINE" { found = 1 } END { exit !found }' "$states"; do
  ((SECONDS < deadline)) || fail "presence didn't come back to ONLINE: $(tail -3 "$states")"
  sleep 2
done
kill "$watcher" 2>/dev/null || true
grep -q ' OFFLINE$' "$states" && fail "a host was shown OFFLINE while Valkey restarted: $(grep -m1 ' OFFLINE$' "$states")"
unknown="$(awk -v from="$restarted" '$1 > from && $2 == "UNKNOWN" { if (!first) first = $1; last = $1 }
  END { if (first) printf "%.1f", last - first; else print "0" }' "$states")"
pass "Valkey restarted: the host was UNKNOWN for about $unknown s and never OFFLINE; a booking made meanwhile waited, then matched and COMPLETED"

# 4. The control-plane pods evicted one at a time, as a drain does: the budget lets one go, refuses the next until the
#    first's replacement is ready, and renters notice nothing.
evict() {
  kubectl create --raw "/api/v1/namespaces/$namespace/pods/$1/eviction" -f - <<JSON 2>&1
{"apiVersion": "policy/v1", "kind": "Eviction", "metadata": {"name": "$1", "namespace": "$namespace"}}
JSON
}
drained="$(now)"
read -r -a before <<<"$(control_plane_pods | tr '\n' ' ')"
((${#before[@]} == 2)) || fail "there are ${#before[@]} control planes running, not 2"
evict "${before[0]}" >/dev/null || fail "the first eviction was refused"
second="$(evict "${before[1]}" || true)"
grep -q "disruption budget" <<<"$second" || fail "the disruption budget let the second control plane go at once: $second"
deadline=$((SECONDS + 300))
until (($(in_namespace get pods -l idleo.in/control-plane=true -o jsonpath='{range .items[*]}{.status.containerStatuses[0].ready}{"\n"}{end}' | grep -c true) == 2)) &&
  ! in_namespace get pod "${before[0]}" >/dev/null 2>&1; do
  ((SECONDS < deadline)) || fail "the first control plane's replacement wasn't ready within 300 s"
  sleep 2
done
until evict "${before[1]}" >/dev/null; do
  ((SECONDS < deadline)) || fail "the second control plane couldn't be evicted"
  sleep 2
done
until (($(in_namespace get pods -l idleo.in/control-plane=true -o jsonpath='{range .items[*]}{.status.containerStatuses[0].ready}{"\n"}{end}' | grep -c true) == 2)) &&
  ! in_namespace get pod "${before[1]}" >/dev/null 2>&1; do
  ((SECONDS < deadline)) || fail "the second control plane's replacement wasn't ready within 300 s"
  sleep 2
done
sleep 5
done_at="$(now)"
reads="$(seen "$drained" "$done_at" read)" writes="$(seen "$drained" "$done_at" write)"
[[ "$reads" == 0\ of* && "$writes" == 0\ of* ]] || fail "renters' requests failed while the control planes were drained: reads $reads, writes $writes"
pass "both control planes evicted one at a time (the budget refused the second until the first's replacement was ready): renters' reads failed $reads, writes $writes"

# 5. The point-in-time restore drill.
for pid in ${pids[@]+"${pids[@]}"}; do kill -TERM "$pid" 2>/dev/null || true; done
pids=()
IDLEO_VERBOSE=1 ops/backup/drill.sh >"$out/failover-test-drill.log" 2>&1 || fail "the restore drill failed; see private/k8s/logs/failover-test-drill.log"
measured="$(grep -E '^drill: run 1: data lost' "$out/failover-test-drill.log" | sed 's/^drill: run 1: //')"
pass "the point-in-time restore drill: $measured (data lost is the RPO; restore to T, the RTO)"

echo
printf '  %s\n' "${summary[@]}"
printf '  %s\n' "in $((SECONDS - started_at)) s"
