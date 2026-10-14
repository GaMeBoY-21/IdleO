#!/usr/bin/env bash
# The whole system on this machine, for local development only: the containers (PostgreSQL, Valkey, object storage,
# the development sign-in server, Mailpit), the control plane on the local profile, two laptops' fake GPUs, the web
# app's dev server and the public site. It never touches production settings or real money.
#
#   ./start.sh                  start everything, and open the web app (Ctrl-C stops it all)
#   ./start.sh --no-browser     the same, without opening a browser (CI, or a machine with no screen)
#   ./start.sh --real-sign-in   sign in with Google too, from control-plane/.env and web/.env.local
#                               (docs/runbooks/identity-provider-setup.md)
#   ./start.sh --fresh          wipe the local database and storage first (it asks)
#   ./start.sh --stop           stop what another ./start.sh started
#   ./start.sh --status         what's running
#
# The steps it shares with scripts/e2e/agent-online.sh are in scripts/lib/stack.sh. Logs go to logs/, agents' keys
# and pids to private/local/ (both gitignored). The first run installs and builds what's missing; later runs skip
# what's already built.
set -euo pipefail

cd "$(dirname "$0")"
root="$(pwd)"

no_browser=false
real_sign_in=false
fresh=false
action=start
for arg in "$@"; do
  case "$arg" in
    --no-browser) no_browser=true ;;
    --real-sign-in) real_sign_in=true ;;
    --fresh) fresh=true ;;
    --stop) action=stop ;;
    --status) action=status ;;
    -h | --help) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "start: unknown option $arg; ./start.sh --help lists them" >&2; exit 2 ;;
  esac
done

http_port=8080
grpc_port=9443
sign_in_port=8099
storage_port=9100
mail_smtp_port=1025
mail_web_port=8025
web_port=3000
site_port=3002
compose_project=idleo-local
out="$root/logs"
state="$root/private/local"
operator_name="${USER:-local}-start"
log() { printf 'start: %s\n' "$*"; }
fail() { printf 'start: %s\n' "$*" >&2; exit 1; }
mkdir -p "$out" "$state"
# shellcheck source=scripts/lib/stack.sh
. "$root/scripts/lib/stack.sh"

# The laptops' fake GPUs: name | GPU memory in MB | price in paise a minute.
laptops=("RTX 4060 Laptop|8188|12" "RTX 4080 Laptop|12282|18")
# The seeded accounts: name | credit in paise.
accounts=("nikki|50000" "nikki-low|100" "nikki-broke|0")
owner=nikki

compose() { COMPOSE_PROJECT_NAME="$compose_project" docker compose -f "$root/control-plane/compose.yaml" "$@"; }

# Everything this script starts, by its pid file; the control plane's is a process group.
stop_all() {
  local pid_file pid
  for pid_file in "$state"/web.pid "$state"/site.pid "$state"/agent-*.pid; do
    [[ -f "$pid_file" ]] || continue
    pid="$(cat "$pid_file")"
    # The web dev server is a process group (pnpm, then Vite); the rest are single processes.
    kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
    rm -f "$pid_file"
  done
  if [[ -f "$state/control-plane.pid" ]]; then
    pid="$(cat "$state/control-plane.pid")"
    kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
    for _ in $(seq 1 60); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 0.5
    done
    kill -KILL -- "-$pid" 2>/dev/null || true
    rm -f "$state/control-plane.pid"
  fi
  # The containers stop with the control plane; this makes sure, and keeps their data for the next start.
  if docker info >/dev/null 2>&1; then
    compose stop >>"$out/compose.log" 2>&1 || true
  fi
}

status() {
  local name pid_file
  for name in control-plane web site agent-1 agent-2; do
    pid_file="$state/$name.pid"
    if [[ -f "$pid_file" ]] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
      printf '  %-14s running (pid %s)\n' "$name" "$(cat "$pid_file")"
    else
      printf '  %-14s stopped\n' "$name"
    fi
  done
  if docker info >/dev/null 2>&1; then
    compose ps --format '  {{.Service}}  {{.State}}' 2>/dev/null | awk '{printf "  %-14s %s\n", $1, $2}' || true
  else
    printf '  containers     Docker is not running\n'
  fi
}

case "$action" in
  stop)
    stop_all
    log "stopped"
    exit 0
    ;;
  status)
    status
    exit 0
    ;;
esac

# Checks first: each says what's wrong and how to fix it, then stops.
if [[ -f "$state/control-plane.pid" ]] && kill -0 "$(cat "$state/control-plane.pid")" 2>/dev/null; then
  fail "it's already running (./start.sh --status shows what); stop it with ./start.sh --stop"
fi
command -v docker >/dev/null || fail "Docker isn't installed (or not on PATH). Install Docker Desktop (Linux: Docker Engine), then run ./start.sh again."
docker info >/dev/null 2>&1 || fail "Docker isn't running. Start Docker Desktop (or the Docker service), then run ./start.sh again."
for dir in /opt/homebrew/opt/openjdk@25/bin /usr/lib/jvm/temurin-25-jdk-*/bin; do
  [[ -d "$dir" ]] && PATH="$dir:$PATH" && export JAVA_HOME="${dir%/bin}"
done
java_major="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
[[ "$java_major" == 25 ]] ||
  fail "Java 25 is needed, found ${java_major:-none}. Install it (macOS: brew install openjdk@25; Linux: Temurin 25) and put it on PATH."
node_want="$(cat "$root/web/.node-version")"
node_have="$(node --version 2>/dev/null | tr -d v)"
[[ "$node_have" == "$node_want" ]] ||
  fail "Node $node_want is needed (web/.node-version), found ${node_have:-none}. Install it (macOS: brew install node@24) and put it on PATH."
command -v corepack >/dev/null || fail "corepack isn't installed; it comes with Node $node_want. Reinstall Node."
command -v rustup >/dev/null ||
  fail "rustup isn't installed. Install it from https://rustup.rs (macOS: brew install rustup), then run ./start.sh again."
rust_want="$(sed -n 's/^channel = "\(.*\)"/\1/p' "$root/agent/rust-toolchain.toml")"
if ! rustup toolchain list | grep -q "^$rust_want"; then
  log "installing Rust $rust_want (agent/rust-toolchain.toml), once"
  (cd "$root/agent" && rustup toolchain install) >>"$out/rust-install.log" 2>&1 ||
    fail "Rust $rust_want couldn't be installed; see logs/rust-install.log"
fi
for port in "$http_port" "$grpc_port" "$sign_in_port" "$storage_port" "$mail_smtp_port" "$mail_web_port" "$web_port" "$site_port"; do
  if port_in_use "$port"; then
    fail "port $port is already in use. Stop what holds it (lsof -iTCP:$port -sTCP:LISTEN shows it), or ./start.sh --stop if it's an earlier start."
  fi
done
if $real_sign_in; then
  [[ -f "$root/web/.env.local" ]] ||
    fail "--real-sign-in needs web/.env.local with your Firebase settings (identity-provider-setup.md, section 7)."
  stack_real_sign_in 1 2> >(sed 's/^/start: /' >&2) || exit 1
fi

if $fresh; then
  printf 'This deletes all local data. Type yes to continue: '
  read -r answer
  [[ "$answer" == yes ]] || fail "nothing deleted"
  compose down --volumes --remove-orphans >>"$out/compose.log" 2>&1 || true
  rm -rf "$state" && mkdir -p "$state"
  log "local data deleted"
fi

# Whatever happens from here, what was started is stopped on the way out: Ctrl-C, a failed step, or the end.
trap 'stop_all' EXIT
trap 'echo; log "stopping…"; exit 0' INT TERM
started=$SECONDS

# The development CA and the codes' keys, made once (control-plane/scripts/dev-ca.sh).
"$root/control-plane/scripts/dev-ca.sh" >>"$out/dev-ca.log" 2>&1 || fail "the development CA couldn't be made; see logs/dev-ca.log"

# The control plane, and meanwhile what can be built beside it.
log "starting the control plane (logs/control-plane.log)"
stack_control_plane_settings() {
  # Anyone signing in locally is let in: no invite step on this machine.
  export IDENTITY_INVITE_ONLY=false
  # The development catalog, whose simulated services the fake GPUs' development runner can run.
  export SESSIONS_TEMPLATES_CATALOG="file:$root/templates/development.yaml"
  # Origins that may call the API from a browser: the dev server, and the public site's pricing page.
  export WEB_ALLOWED_ORIGINS="http://localhost:$web_port,http://localhost:$site_port"
}
stack_start_control_plane
echo "$control_plane_pid" >"$state/control-plane.pid"

web_env=(IDLEO_DEPLOYMENT=local VITE_API_URL="$api" VITE_STORAGE_URL="http://localhost:$storage_port")
if ! $real_sign_in; then
  # The mock sign-in, whatever web/.env.local says: the environment wins over .env files in Vite.
  web_env+=(VITE_AUTH=oidc-mock VITE_OIDC_ISSUER="$sign_in" VITE_OIDC_CLIENT_ID=idleo-local)
fi
if [[ ! -d "$root/web/node_modules" || "$root/web/pnpm-lock.yaml" -nt "$root/web/node_modules/.modules.yaml" ]]; then
  log "installing the web app's packages, once"
  (cd "$root/web" && pnpm install --frozen-lockfile) >>"$out/web-install.log" 2>&1 ||
    fail "pnpm install failed; see logs/web-install.log"
fi
log "building the agent (logs/agent-build.log)"
stack_build_agent &
agent_build_pid=$!

# The public site: built again only when its sources changed since the last build.
site_dist="$root/web/dist-local"
site_stamp="$state/site.stamp"
# (The API types and tokens are generated into web/src on every start, so they don't count.)
newest_source="$(find "$root/web/src" "$root/web/scripts" "$root/docs/site" "$root/templates" "$root/api/openapi.yaml" \
  -type f ! -name '*.gen.*' -newer "$site_stamp" 2>/dev/null | head -1 || true)"
if [[ ! -f "$site_stamp" || ! -d "$site_dist" || -n "$newest_source" ]]; then
  log "building the public site (logs/site-build.log)"
  (cd "$root/web" && env "${web_env[@]}" IDLEO_DIST=dist-local IDLEO_SITE_DIST=dist-site-local pnpm build) \
    >"$out/site-build.log" 2>&1 || fail "the public site's build failed; see logs/site-build.log"
  touch "$site_stamp"
fi
(cd "$root/web" && IDLEO_DIST=dist-local PORT="$site_port" exec node scripts/serve.ts) >>"$out/site.log" 2>&1 &
echo $! >"$state/site.pid"

# The web app's dev server, with live reload (pnpm dev makes the API types and tokens first), in its own process
# group, so stopping it reaches Vite under pnpm too.
set -m
(cd "$root/web" && exec env "${web_env[@]}" pnpm dev --port "$web_port" --strictPort) >>"$out/web.log" 2>&1 &
echo $! >"$state/web.pid"
set +m

wait "$agent_build_pid" || fail "the agent's build failed; see logs/agent-build.log"
stack_await_control_plane 600
stack_await_sign_in

# The accounts, idempotently: created by signing in once, credit granted only up to what each should have.
account_id() { curl -sf "$api/v1/me" -H "Authorization: Bearer $1" | json_get .id; }
for entry in "${accounts[@]}"; do
  IFS='|' read -r name paise <<<"$entry"
  token="$(stack_token "$name")"
  [[ -n "$token" ]] || fail "no token for $name from the development sign-in server"
  id="$(account_id "$token")"
  have="$(curl -sf "$api/v1/wallet" -H "Authorization: Bearer $token" | json_get .balancePaise)"
  if ((paise > 0 && have == 0)); then
    log "giving $name@example.com ₹$((paise / 100)).$(printf '%02d' $((paise % 100))) of credit"
    ops grant-credits --user "$id" --paise "$paise" --reason "'Local stack credit'" --key "local-$name-$paise" ||
      fail "grant-credits failed; see logs/ops.log"
  fi
done

# The two laptops: registered, enrolled and activated once, each listed at its price, then run.
owner_token="$(stack_token "$owner")"
hosts_json="$(curl -sf "$api/v1/hosts" -H "Authorization: Bearer $owner_token")"
for i in 1 2; do
  IFS='|' read -r model vram price <<<"${laptops[$((i - 1))]}"
  host_id="$(printf '%s' "$hosts_json" | python3 -c 'import json, sys
hosts = [h for h in json.load(sys.stdin)["hosts"] if h["name"] == sys.argv[1] and h["status"] != "removed"]
print(hosts[0]["id"] if hosts else "")' "$model")"
  # Enrolled once: the agent keeps its certificate and key in its data folder (host.crt, host.key).
  if [[ -z "$host_id" || ! -f "$state/agent-$i/host.crt" ]]; then
    log "adding the $model (enrolled once)"
    [[ -n "$host_id" ]] || host_id="$(stack_register_host "$owner_token" "$model" "local-host-$i")"
    rm -rf "$state/agent-$i"
    stack_agent_config "$i"
    stack_enrol_agent "$i" "$(stack_enrolment_code "$owner_token" "$host_id")"
  fi
  curl -sf -X PUT "$api/v1/hosts/$host_id/listing" -H "Authorization: Bearer $owner_token" -H 'Content-Type: application/json' \
    -d "{\"batchPricePaisePerMin\": $price, \"gpuTempCapC\": 85, \"maxConcurrentSessions\": 1, \"keepFreeGb\": 10}" >/dev/null ||
    fail "the $model's price couldn't be set"
  stack_run_agent "$i" --fake-gpu-model "$model" --fake-gpu-vram-mb "$vram" --fake-free-disk-mb 200000
  host_ids[i]="$host_id"
done
# Online first: a host is activated only once its agent has reported its GPU.
for i in 1 2; do
  deadline=$((SECONDS + 60))
  until curl -sf "$api/v1/hosts/${host_ids[$i]}" -H "Authorization: Bearer $owner_token" | grep -q '"online":true'; do
    agent_running "$i" || fail "agent $i stopped; see logs/agent-$i.log"
    ((SECONDS < deadline)) || fail "the ${laptops[$((i - 1))]%%|*} didn't come online; see logs/agent-$i.log"
    sleep 0.5
  done
  if [[ "$(curl -sf "$api/v1/hosts/${host_ids[$i]}" -H "Authorization: Bearer $owner_token" | json_get .status)" != active ]]; then
    log "activating the ${laptops[$((i - 1))]%%|*}, once"
    ops activate-host --host "${host_ids[$i]}" --reason "'Local stack host'" || fail "activate-host failed; see logs/ops.log"
  fi
done

# Healthy before saying so: every service answers, and both laptops are online.
await() {
  local what="$1" url="$2" deadline=$((SECONDS + 90))
  until curl -sf -o /dev/null "$url"; do
    ((SECONDS < deadline)) || fail "$what didn't answer at $url; see logs/"
    sleep 0.5
  done
}
await "the API" "$api/actuator/health/readiness"
await "the sign-in server" "$sign_in/.well-known/openid-configuration"
await "object storage" "http://localhost:$storage_port/minio/health/live"
await "Mailpit" "http://localhost:$mail_web_port/api/v1/info"
await "the web app" "http://localhost:$web_port/"
await "the public site" "http://localhost:$site_port/about/"
for i in 1 2; do
  curl -sf "$api/v1/hosts/${host_ids[$i]}" -H "Authorization: Bearer $owner_token" | grep -q '"online":true' ||
    fail "the ${laptops[$((i - 1))]%%|*} went offline; see logs/agent-$i.log"
done

cat <<READY

Everything is up, in $((SECONDS - started)) s.

  Web app        http://localhost:$web_port
  Public site    http://localhost:$site_port/about/
  Email          http://localhost:$mail_web_port   (Mailpit: every email the stack sends, none delivered)
  Admin console  http://localhost:$web_port/admin/   (needs the admin role: docs/runbooks/admin.md)
  API            $api

  Sign in as     nikki@example.com (₹500, owns both laptops)
                 nikki-low@example.com (₹1)
                 nikki-broke@example.com (₹0)
READY
if $real_sign_in; then
  printf '                 or with Google, project %s\n' "$real_sign_in_project"
fi
cat <<READY
  Laptops        RTX 4060 Laptop (8 GB, ₹7.20/h), RTX 4080 Laptop (12 GB, ₹10.80/h), online

  Logs           logs/  (control-plane, web, site, agent-1, agent-2, ops)
  Stop           Ctrl-C here, or ./start.sh --stop from another terminal

READY
log "ready"
if ! $no_browser; then
  if command -v open >/dev/null; then open "http://localhost:$web_port"; elif command -v xdg-open >/dev/null; then xdg-open "http://localhost:$web_port" >/dev/null 2>&1 || true; fi
fi

# Until Ctrl-C (or ./start.sh --stop): if the control plane stops, everything stops.
while kill -0 "$control_plane_pid" 2>/dev/null; do
  sleep 2
done
if [[ ! -f "$state/control-plane.pid" ]]; then
  log "stopped by ./start.sh --stop"
  exit 0
fi
log "the control plane stopped; stopping the rest (logs/control-plane.log says why)"
exit 1
