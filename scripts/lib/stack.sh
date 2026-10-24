# The local stack's shared steps, sourced by scripts/e2e/agent-online.sh and ./start.sh, so the two start the
# system the same way and can't drift apart: the toolchains on PATH, the port check, the control plane on the local
# profile (which starts compose.yaml), its readiness, the development sign-in server and its tokens, operator
# commands, hosts registered and enrolled, and fake-GPU agents built, configured and run.
#
# The caller sets, before sourcing: root (the repository), out (a gitignored folder for logs, and keys and pids unless
# state names another),
# http_port, grpc_port, sign_in_port, storage_port, mail_smtp_port, mail_web_port, web_port, compose_project, and
# razorpay_port for the fake Razorpay (stack_start_fake_razorpay; left unset, payments are off). It
# defines log and fail (each script prints its own prefix), and may set operator_name, the name operator commands
# record in the audit log. After sourcing, api, sign_in and agent are set.
# shellcheck shell=bash

api="http://localhost:$http_port"
sign_in="http://localhost:$sign_in_port/idleo-local"
agent="$root/agent/target/release/idleo-agent"
# Email (ADR 0026): what the stack's Mailpit has kept, read through its API.
mail_api="http://localhost:${mail_web_port:-8025}/api/v1"
# Where logs are, as messages name it: relative to the repository.
logs_dir="${out#"$root"/}"
# Agents' configs, keys and pids; beside the logs unless the caller keeps them apart.
state="${state:-$out}"

# shellcheck source=scripts/lib/toolchains.sh
. "$root/scripts/lib/toolchains.sh"

now_ms() { perl -MTime::HiRes=time -e 'printf "%d\n", time * 1000'; }
seconds_since() { perl -e 'printf "%.2f", ($ARGV[1] - $ARGV[0]) / 1000' "$1" "$(now_ms)"; }
# The string value of a JSON field that appears once, whatever the spacing or line breaks.
json_field() { tr -d '\n' | sed -n "s/^[^{]*{.*\"$1\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p"; }
port_in_use() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
# A value from JSON on stdin, by a path like .wallet.reservedPaise.
json_get() { python3 -c 'import json, sys
value = json.load(sys.stdin)
for key in sys.argv[1].strip(".").split("."):
    value = value[int(key)] if isinstance(value, list) else value[key]
print(value)' "$1"; }

# An operator command (docs/runbooks/granting-credits.md, hosts.md) against the running stack.
ops() {
  (
    cd "$root/control-plane"
    LOCAL_SIGN_IN_PORT="$sign_in_port" COMPOSE_PROJECT_NAME="$compose_project" OPERATOR="${operator_name:-local-stack}" \
      ./mvnw -B -q spring-boot:run -Dspring-boot.run.profiles=local,ops -Dspring-boot.run.arguments="$*"
  ) >>"$out/ops.log" 2>&1
}

# Starts the control plane on the local profile, which starts compose.yaml, in its own process group (so teardown
# reaches Maven's forked JVM too), logging to $out/control-plane.log; sets control_plane_pid. .env is never read:
# the stack doesn't depend on a developer's local overrides. A caller's stack_control_plane_settings, when defined,
# runs just before the start and exports what that caller wants beside the common settings.
stack_start_control_plane() {
  set -m
  (
    cd "$root/control-plane"
    unset IDENTITY_ISSUERS_0_ISSUERURI IDENTITY_ISSUERS_0_AUDIENCE SPRING_PROFILES_ACTIVE SPRING_PROFILES_INCLUDE
    export SERVER_PORT="$http_port" SPRING_GRPC_SERVER_PORT="$grpc_port" LOCAL_SIGN_IN_PORT="$sign_in_port"
    export LOCAL_STORAGE_PORT="$storage_port" COMPOSE_PROJECT_NAME="$compose_project"
    # Email goes to Mailpit from compose.yaml (ADR 0026), and its links to this stack's web app.
    export LOCAL_MAIL_SMTP_PORT="$mail_smtp_port" LOCAL_MAIL_WEB_PORT="$mail_web_port"
    export NOTIFICATIONS_WEB_APP_URL="http://localhost:$web_port"
    # Where a CLI sign-in and a host link are approved (ADR 0025, ADR 0032): this stack's web app.
    export IDENTITY_WEB_APP_URL="http://localhost:$web_port" HOSTS_LINK_WEB_APP_URL="http://localhost:$web_port"
    # The web app's origin, which may call the API from a browser (ADR 0018), and the website's when the script has one
    # (its pricing page asks for live prices, ADR 0029).
    export WEB_ALLOWED_ORIGINS="http://localhost:$web_port${site_port:+,http://localhost:$site_port}"
    # The Windows app's release feed (ADR 0034) trusts the development release key when there is one
    # (scripts/release/dev-key.sh), or the caller's IDLEO_RELEASE_PUBLIC_KEY, and reads the feed every 10 s, so a
    # release published to this stack's storage shows at once.
    if [[ -z "${IDLEO_RELEASE_PUBLIC_KEY:-}" && -s "$root/private/release-dev/release.pub" ]]; then
      IDLEO_RELEASE_PUBLIC_KEY="$(tr -d '[:space:]' <"$root/private/release-dev/release.pub")"
    fi
    export IDLEO_RELEASE_PUBLIC_KEY="${IDLEO_RELEASE_PUBLIC_KEY:-}" HOSTS_RELEASES_REFRESH_EVERY=10s
    # Payments (ADR 0039) go to the fake Razorpay when the stack started one, with the fake's own test keys, which no
    # real Razorpay account has; production refuses any API but Razorpay's (ProductionSettingsGuard).
    if [[ -n "${razorpay_port:-}" ]]; then
      export RAZORPAY_API_URL="http://localhost:$razorpay_port" RAZORPAY_KEY_ID=rzp_test_idleofake00001
      export RAZORPAY_KEY_SECRET=fake-razorpay-key-secret RAZORPAY_WEBHOOK_SECRET=fake-razorpay-webhook-secret
    fi
    if declare -F stack_control_plane_settings >/dev/null; then
      stack_control_plane_settings
    fi
    exec ./mvnw -B -q spring-boot:run -Dspring-boot.run.profiles=local
  ) >"$out/control-plane.log" 2>&1 &
  control_plane_pid=$!
  set +m
}

# Starts the fake Razorpay (ADR 0039: control-plane/src/test/.../razorpay/FakeRazorpayServer.java) on $razorpay_port,
# from the control plane's test classpath, so it's never in the control plane's jar: Razorpay's API from its documented
# samples, the browser's fake Checkout at /v1/checkout.js, and Razorpay's webhooks to this stack's control plane. It
# compiles the tests, so call it before stack_start_control_plane, never beside another Maven run. Sets
# fake_razorpay_pid and writes $state/fake-razorpay.pid; logs to $out/fake-razorpay.log.
stack_start_fake_razorpay() {
  local classpath="$state/fake-razorpay.classpath"
  (
    cd "$root/control-plane"
    ./mvnw -B -q test-compile dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile="$classpath"
  ) >"$out/fake-razorpay-build.log" 2>&1 || fail "the fake Razorpay didn't build; see $logs_dir/fake-razorpay-build.log"
  java -cp "$root/control-plane/target/test-classes:$(cat "$classpath")" \
    com.idleo.controlplane.razorpay.FakeRazorpayServer "$razorpay_port" "$api/v1/payments/razorpay/webhook" \
    >"$out/fake-razorpay.log" 2>&1 &
  fake_razorpay_pid=$!
  echo "$fake_razorpay_pid" >"$state/fake-razorpay.pid"
  local deadline=$((SECONDS + 30))
  until curl -fsS -o /dev/null "http://localhost:$razorpay_port/v1/checkout.js"; do
    kill -0 "$fake_razorpay_pid" 2>/dev/null || fail "the fake Razorpay exited; see $logs_dir/fake-razorpay.log"
    ((SECONDS < deadline)) || fail "the fake Razorpay didn't answer within 30 s; see $logs_dir/fake-razorpay.log"
    sleep 0.5
  done
}

# Starts compose.yaml's services (all, or the ones named) before the control plane does, with the settings the control plane
# will start with, for a caller that needs one first (the registry, to push an image whose digest the catalog pins). The
# control plane's own compose support then finds them running and starts nothing; had only the registry been started, it
# would think the stack up and find no database.
stack_compose_up() {
  (
    export COMPOSE_PROJECT_NAME="$compose_project" LOCAL_SIGN_IN_PORT="$sign_in_port" LOCAL_STORAGE_PORT="$storage_port"
    export LOCAL_MAIL_SMTP_PORT="$mail_smtp_port" LOCAL_MAIL_WEB_PORT="$mail_web_port"
    export WEB_ALLOWED_ORIGINS="http://localhost:$web_port${site_port:+,http://localhost:$site_port}"
    if declare -F stack_control_plane_settings >/dev/null; then
      stack_control_plane_settings
    fi
    docker compose -f "$root/control-plane/compose.yaml" up -d "$@"
  )
}

# Waits until the control plane is ready, for at most $1 seconds.
stack_await_control_plane() {
  local deadline=$((SECONDS + $1))
  until curl -sf "$api/actuator/health/readiness" 2>/dev/null | grep -q '"status":"UP"'; do
    kill -0 "$control_plane_pid" 2>/dev/null || fail "the control plane exited during startup; see $logs_dir/control-plane.log"
    ((SECONDS < deadline)) || fail "the control plane wasn't ready within $1 s"
    sleep 1
  done
}

# Compose only waits for the sign-in server's port, so wait for its discovery document too.
stack_await_sign_in() {
  local deadline=$((SECONDS + 60))
  until curl -sf "$sign_in/.well-known/openid-configuration" >/dev/null 2>&1; do
    ((SECONDS < deadline)) || fail "the development sign-in server didn't answer within 60 s"
    sleep 0.5
  done
}

# A token from the development sign-in server for the user $1 (their address is $1@example.com). The account is
# made, and agrees to the Terms and privacy notice in force as a person would on their first visit (ADR 0037), so the
# app doesn't stop the scripts' people at that step; a journey that's about that step signs in someone new instead.
stack_token() {
  local token
  token="$(curl -sf -X POST "$sign_in/token" \
    -d grant_type=client_credentials -d "client_id=$1" -d client_secret=unused -d scope=openid -d "auth_time=$(date +%s)" |
    json_field access_token)"
  stack_agree "$token"
  printf '%s\n' "$token"
}

# Agrees, as the person behind token $1, to the Terms and privacy notice in force, with their age.
stack_agree() {
  local notices
  notices="$(curl -sf "$api/v1/me" -H "Authorization: Bearer $1" |
    python3 -c 'import json, sys; n = json.load(sys.stdin)["notices"]
print(json.dumps({"termsVersion": n["termsVersion"], "privacyVersion": n["privacyVersion"], "ageConfirmed": True}))')" ||
    return 0
  curl -sf -o /dev/null -X POST "$api/v1/me/notices" -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -d "$notices" || true
}

# Registers a host named $2 for the owner whose token is $1, once for the Idempotency-Key $3; prints its id.
stack_register_host() {
  local host_json id
  host_json="$(curl -sf -X POST "$api/v1/hosts" -H "Authorization: Bearer $1" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: \"$3\"" -d "{\"name\":\"$2\"}")" ||
    fail "POST /v1/hosts failed"
  id="$(printf '%s' "$host_json" | json_field id)"
  [[ -n "$id" ]] || fail "POST /v1/hosts returned no id"
  printf '%s' "$id"
}

# A one-time enrolment code for the host $2 of the owner whose token is $1. Never print or log it.
stack_enrolment_code() {
  local code
  code="$(curl -sf -X POST "$api/v1/hosts/$2/enrolment-code" -H "Authorization: Bearer $1" | json_field code)"
  [[ -n "$code" ]] || fail "no enrolment code"
  printf '%s' "$code"
}

# Builds the agent from inside agent/, so rustup uses the toolchain pinned in agent/rust-toolchain.toml; it looks in
# the current directory, not at --manifest-path.
stack_build_agent() {
  (cd "$root/agent" && cargo build --release --locked --quiet) >"$out/agent-build.log" 2>&1 ||
    fail "cargo build failed; see $logs_dir/agent-build.log"
}

# Writes agent $1's config, with its own data folder, trusting the development CA.
stack_agent_config() {
  cat >"$state/agent-$1.toml" <<TOML
ca_root = "$root/private/dev-ca/root.pem"
data_dir = "$state/agent-$1"
log_format = "text"

[control_plane]
host = "localhost"
port = $grpc_port
TOML
}

# Enrols agent $1 with the one-time code $2.
stack_enrol_agent() {
  "$agent" --config "$state/agent-$1.toml" enrol --code "$2" >>"$out/agent-$1.log" 2>&1 ||
    fail "enrolment failed; see $logs_dir/agent-$1.log"
}

# Runs agent $1 with a fake GPU and the development runner, in the background; its pid goes to $state/agent-$1.pid.
# The rest of the arguments are the agent's --fake-gpu options (model, memory, free disk). With stack_runner set (a real
# runner's `--container-runner docker:<container>`, ./start.sh --real-runner) the jobs run in real containers instead, and
# set to nothing the agent has no runner and declines offers.
stack_run_agent() {
  local n="$1"
  shift
  IDLEO_LOG=info "$agent" --config "$state/agent-$n.toml" run --fake-gpu "$@" ${stack_runner---dev-runner} >>"$out/agent-$n.log" 2>&1 &
  echo $! >"$state/agent-$n.pid"
}
agent_running() { [[ -f "$state/agent-$1.pid" ]] && kill -0 "$(cat "$state/agent-$1.pid")" 2>/dev/null; }

# Real sign-in, shared with control-plane/scripts/run-local.sh.
# shellcheck source=scripts/lib/real-sign-in.sh
. "$root/scripts/lib/real-sign-in.sh"

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
  printf 'FAIL: no email to %s holding "%s" within %s s\n' "$1" "$2" "${3:-60}" >&2
  return 1
}
