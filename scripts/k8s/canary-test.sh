#!/usr/bin/env bash
# The proof of GitOps and canary releases (ADR 0046; .github/workflows/gitops.yml runs it on pull requests that touch
# deploy/, and weekly), on the Mac's cluster under Flux:
#
#   1. up.sh --gitops: Flux bootstrapped from this commit on GitHub; the chart's signature checked before it's
#      installed; the Secrets decrypted from SOPS; the release installed, the control plane as Flagger's primary;
#   2. a good release (the same control plane as a new version) rolls through every step, 10, 25, 50 and 100%, to
#      promotion, after its migration Job, with renters' requests through the Gateway answered all along; timed;
#   3. a bad release (a development build that fails 30% of API calls on purpose, web.faults.server-error-percent,
#      which the prod profile refuses) is rolled back by Flagger alone at its first failed check; timed from its start
#      to the rollback; renters' requests are back on the good version, and the signal CanaryFailed pages on is set;
#   4. a migration that drops a column without a contract line is refused by the CI check (scripts/checks/migrations.sh);
#   5. an agent built from the previous release (the commit this one is based on) enrols, stays connected and runs a
#      booking to the end against the new control plane while the canary runs both versions.
#
# Logs: private/k8s/logs/ (canary-test.log, agent-old.log). It leaves the cluster running, on the good release with
# the bad one marked failed (Flux's idleo Kustomization says so too); scripts/k8s/down.sh deletes it.
set -euo pipefail
. "$(dirname "$0")/../lib/quiet.sh"
quiet_entry k8s-canary-test "$@"
set -- ${QUIET_ARGS[@]+"${QUIET_ARGS[@]}"}
log() { printf 'canary-test: %s\n' "$*"; }
fail() { printf 'canary-test: FAIL: %s\n' "$*" >&2; exit 1; }
pass() { printf 'canary-test: PASS: %s\n' "$*"; summary+=("$*"); }
. "$(dirname "$0")/lib.sh"
cd "$k8s_root"
root="$k8s_root"
summary=()
out="$k8s_state/logs"
state="$k8s_state/canary-test"
mkdir -p "$out" "$state"
started_at=$SECONDS

# 1. The cluster under Flux.
log "bringing the cluster up under Flux (up.sh --gitops)"
scripts/k8s/up.sh --gitops || fail "up.sh --gitops failed; see private/k8s/logs/up.log"
in_namespace() { kubectl -n "$namespace" "$@"; }
verified="$(in_namespace get ocirepository idleo-chart -o jsonpath='{.status.conditions[?(@.type=="SourceVerified")].status}')"
[[ "$verified" == True ]] || fail "Flux didn't verify the chart's signature: kubectl -n $namespace describe ocirepository idleo-chart"
phase="$(in_namespace get canary idleo-control-plane -o jsonpath='{.status.phase}')"
[[ "$phase" == Initialized || "$phase" == Succeeded ]] || fail "the canary is $phase, not Initialized"
tag="$(kubectl -n flux-system get configmap local-release -o jsonpath='{.data.IMAGE_TAG}')"
chart_digest="$(kubectl -n flux-system get configmap local-release -o jsonpath='{.data.CHART_DIGEST}')"
pass "Flux bootstrapped from GitHub at $(git rev-parse --short HEAD); the chart's signature verified; the Secrets decrypted; the control plane is Flagger's primary ($phase), $((SECONDS - started_at)) s"

# The stack's shared steps (scripts/lib/stack.sh), pointed at the cluster, for the agent and the renter.
api="$api_url"
sign_in="$sign_in_url"
grpc_port="$agents_port"
operator_name=k8s-canary-test
agent_ca_root="$k8s_state/agent-ca.pem"
# shellcheck source=scripts/lib/stack.sh
. "$root/scripts/lib/stack.sh"
export CURL_CA_BUNDLE="$local_ca/ca.pem"
ops() { OPERATOR="$operator_name" scripts/k8s/ops.sh "$@" >>"$out/canary-test-ops.log" 2>&1; }
pids=()
cleanup() {
  local pid
  for pid in ${pids[@]+"${pids[@]}"}; do kill -TERM "$pid" 2>/dev/null || true; done
  git worktree remove --force "$state/previous" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# A control plane image for a release: this build's, with a new version label, and any environment it's given.
release_image() {
  local name="$1" dockerfile
  shift
  dockerfile="$(printf 'FROM localhost:%s/idleo-control-plane:%s\nLABEL org.opencontainers.image.version=%s\n' \
    "$registry_port" "$tag" "$name")"
  for setting in "$@"; do dockerfile+=$'\n'"ENV $setting"; done
  docker build -q -t "localhost:$registry_port/idleo-control-plane:$name" - <<<"$dockerfile" >/dev/null ||
    fail "the $name image didn't build"
  local_push "idleo-control-plane:$name" 2>>"$out/canary-test.log" || fail "the $name image couldn't be pushed"
}

# Releases control plane image $2 under tag $1, as a merged pull request would: Flux reads the new release and
# upgrades the chart, the migration Job runs, and Flagger starts the canary.
release() {
  release_settings "$1" "$chart_digest" "$2"
  flux_reconcile
}

# Follows the canary until its phase matches $1 or $2 seconds pass, recording each weight it takes and when it first
# began progressing; fails on a phase matching $3.
weights="$state/weights"
follow_canary() {
  local deadline=$((SECONDS + $2)) phase weight
  : >"$weights"
  progressing_at=""
  while ((SECONDS < deadline)); do
    phase="$(in_namespace get canary idleo-control-plane -o jsonpath='{.status.phase}')"
    weight="$(in_namespace get canary idleo-control-plane -o jsonpath='{.status.canaryWeight}')"
    [[ "$phase" == Progressing && -z "$progressing_at" ]] && progressing_at=$SECONDS
    grep -qx "${weight:-0}" "$weights" || echo "${weight:-0}" >>"$weights"
    [[ "$phase" =~ ^($1)$ ]] && return 0
    [[ -n "${3:-}" && "$phase" =~ ^($3)$ ]] && fail "the canary went $phase; kubectl -n $namespace describe canary idleo-control-plane"
    sleep 3
  done
  fail "the canary wasn't $1 within $2 s (it's $phase); kubectl -n $namespace describe canary idleo-control-plane"
}

# Renters' requests through the Gateway, one every 1.5 s (under the 60 a minute an address may make), each status
# written with the time: what a renter saw during each release.
requests="$state/requests"
: >"$requests"
(
  while true; do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$api/v1/public/availability" || echo 000)"
    printf '%s %s\n' "$SECONDS" "$code" >>"$requests"
    sleep 1.5
  done
) &
pids+=($!)
answered_between() { awk -v from="$1" -v to="$2" '$1 >= from && $1 <= to { print $2 }' "$requests"; }

# 5, set up. The previous release's agent: built from the commit this one is based on (on main, its parent).
previous="$(git merge-base HEAD origin/main)"
[[ "$previous" == "$(git rev-parse HEAD)" ]] && previous="$(git rev-parse HEAD^)"
log "building the agent of the previous release (${previous:0:12})"
git worktree remove --force "$state/previous" >/dev/null 2>&1 || true
git worktree add --detach "$state/previous" "$previous" >/dev/null 2>&1 || fail "couldn't check out $previous"
(cd "$state/previous/agent" && CARGO_TARGET_DIR="$k8s_state/previous-agent-target" cargo build --release --locked --quiet) \
  >"$out/agent-old-build.log" 2>&1 || fail "the previous release's agent didn't build; see private/k8s/logs/agent-old-build.log"
agent="$k8s_state/previous-agent-target/release/idleo-agent"
user="canary-$(openssl rand -hex 4)"
token="$(stack_token "$user")"
api_get() { curl -sf "$api$1" -H "Authorization: Bearer $token"; }
user_id="$(api_get /v1/me | json_get .id)"
host_id="$(stack_register_host "$token" "Previous release's agent" "$user-host")"
rm -rf "$state/agent-old"
stack_agent_config old
stack_enrol_agent old "$(stack_enrolment_code "$token" "$host_id")"
stack_run_agent old --fake-gpu-model "RTX 4060 Laptop" --fake-gpu-vram-mb 8192 --fake-free-disk-mb 200000
pids+=("$(cat "$state/agent-old.pid")")
host_online() { [[ "$(api_get "/v1/hosts/$host_id" | json_get .online)" == True ]]; }
deadline=$((SECONDS + 90))
until host_online; do
  ((SECONDS < deadline)) || fail "the previous release's agent didn't come online; see private/k8s/logs/agent-old.log"
  sleep 2
done
curl -sf -X PUT "$api/v1/hosts/$host_id/listing" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  -d '{"batchPricePaisePerMin": 2, "gpuTempCapC": 85, "maxConcurrentSessions": 1, "keepFreeGb": 10}' >/dev/null ||
  fail "the host's listing couldn't be set"
ops activate-host --host "$host_id" --reason "'Canary test'" || fail "activate-host failed"
ops grant-credits --user "$user_id" --paise 10000 --reason "'Canary test'" --key "$user" || fail "grant-credits failed"

# 2. A good release.
good="$tag-good"
good_digest="$(release_image "$good")"
log "releasing $good: Flagger steps it through 10, 25, 50 and 100% of the API's traffic, two minutes a step"
release "$good" "$good_digest"
released=$SECONDS
# During the canary, the old agent runs a booking to the end on whichever version its stream is on.
until [[ "$(in_namespace get canary idleo-control-plane -o jsonpath='{.status.phase}')" == Progressing ]]; do
  ((SECONDS - released < 600)) || fail "the canary didn't start within 600 s; kubectl -n flux-system describe kustomization idleo"
  sleep 3
done
session="$(curl -sf -X POST "$api/v1/sessions" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: \"$user-session\"" \
  -d '{"templateId": "cuda-smoke-test", "maxPricePaisePerMin": 5, "maxSpendPaise": 1000}' | json_get .id)" ||
  fail "POST /v1/sessions failed during the canary"
follow_canary Succeeded 1500 Failed
good_seconds=$((SECONDS - released))
migrated_at="$(in_namespace get job idleo-migrate -o jsonpath='{.status.completionTime}')"
seen="$(sort -n "$weights" | tr '\n' ' ')"
for step in 10 25 50 100; do grep -qx "$step" "$weights" || fail "the canary never took $step% (it took: $seen)"; done
[[ "$(in_namespace get deployment idleo-control-plane-primary -o jsonpath='{.spec.template.spec.containers[0].image}')" == *"$good_digest" ]] ||
  fail "the primary isn't the good release after promotion"
good_codes="$(answered_between "$released" "$SECONDS")"
bad_answers="$(grep -cv '^200$' <<<"$good_codes" || true)"
((bad_answers == 0)) || fail "$bad_answers of $(wc -l <<<"$good_codes") renters' requests failed during the good release"
pass "a good release went through ${seen}% to promotion in $good_seconds s (migration Job done at $migrated_at, before the canary); all $(wc -l <<<"$good_codes" | tr -d ' ') renters' requests answered 200"

# 5. The previous release's agent, through the canary.
deadline=$((SECONDS + 300))
until [[ "$(api_get "/v1/sessions/$session" | json_get .state)" == COMPLETED ]]; do
  ((SECONDS < deadline)) || fail "the old agent's session $session didn't complete (it's $(api_get "/v1/sessions/$session" | json_get .state))"
  sleep 2
done
kill -0 "$(cat "$state/agent-old.pid")" 2>/dev/null || fail "the previous release's agent exited; see private/k8s/logs/agent-old.log"
host_online || fail "the previous release's agent isn't online after the canary"
pass "the previous release's agent (${previous:0:12}) stayed online through the canary and ran a booking to COMPLETED against both versions"

# 3. A bad release.
bad="$tag-bad"
bad_digest="$(release_image "$bad" WEB_FAULTS_SERVERERRORPERCENT=30)"
log "releasing $bad, which fails 30% of API calls: Flagger should roll it back at its first failed check"
release "$bad" "$bad_digest"
released=$SECONDS
follow_canary Failed 900 Succeeded
bad_seconds=$((SECONDS - released))
from_first_weight=$((SECONDS - ${progressing_at:-$released}))
reason="$(in_namespace get canary idleo-control-plane -o jsonpath='{.status.conditions[?(@.type=="Promoted")].message}')"
weight="$(in_namespace get canary idleo-control-plane -o jsonpath='{.status.canaryWeight}')"
[[ "${weight:-0}" == 0 ]] || fail "the canary still has $weight% of the traffic after it failed"
[[ "$(in_namespace get deployment idleo-control-plane-primary -o jsonpath='{.spec.template.spec.containers[0].image}')" == *"$good_digest" ]] ||
  fail "the primary isn't the good release after the rollback"
# Renters' requests afterwards: all to the good version (the bad one fails 30%: a minute of them would show it).
sleep 60
after="$(answered_between "$((SECONDS - 60))" "$SECONDS")"
after_failed="$(grep -c '^5' <<<"$after" || true)"
((after_failed == 0)) || fail "$after_failed of $(wc -l <<<"$after") renters' requests still failed after the rollback"
during="$(answered_between "$released" "$((SECONDS - 60))")"
pass "a bad release (30% of API calls fail) was rolled back by Flagger in $bad_seconds s from its release ($from_first_weight s after its canary began): \"$reason\"; $(grep -c '^5' <<<"$during" || true) of $(wc -l <<<"$during" | tr -d ' ') renters' requests failed while it had traffic, none of $(wc -l <<<"$after" | tr -d ' ') after"

# The signal ops/alerts' CanaryFailed pages on (through Grafana Cloud in staging and production): the in-cluster
# Prometheus has Flagger saying this canary failed.
query='max by (name, namespace) (flagger_canary_status{job="flagger"}) == 2'
deadline=$((SECONDS + 120))
until alerting="$(kubectl -n monitoring exec deploy/prometheus -- wget -qO- \
  "http://localhost:9090/api/v1/query?query=$(python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$query")")" &&
  grep -q '"name":"idleo-control-plane"' <<<"$alerting"; do
  ((SECONDS < deadline)) || fail "Prometheus doesn't have the canary's failure (CanaryFailed's expression): $alerting"
  sleep 10
done
health="$(kubectl -n flux-system get kustomization idleo -o jsonpath='{.status.conditions[?(@.type=="Ready")].message}')"
pass "CanaryFailed's expression is true in the in-cluster Prometheus; Flux's idleo Kustomization says: $health"

# 4. An incompatible migration.
migrations="$state/migrations"
rm -rf "$migrations"
cp -R control-plane/src/main/resources/db/migration "$migrations"
printf 'alter table sessions.sessions drop column template_id;\n' >"$migrations/V9999__drop_without_a_contract.sql"
if refused="$(scripts/checks/migrations.sh "$migrations" 2>&1)"; then
  fail "the migration check let a DROP without a contract line through"
fi
grep -q "V9999__drop_without_a_contract.sql: DROP" <<<"$refused" || fail "the migration check refused it for another reason: $refused"
pass "a migration dropping a column without a contract line is refused by scripts/checks/migrations.sh, which prints the rule"

echo
printf '  %s\n' "${summary[@]}"
printf '  %s\n' "in $((SECONDS - started_at)) s"
