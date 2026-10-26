#!/usr/bin/env bash
# The whole system on this machine, for local development only: the containers (PostgreSQL, Valkey, object storage,
# the development sign-in server, Mailpit, and Grafana with its Prometheus, Loki and Tempo and the alerts' sender), the
# fake Razorpay (payments with no real money, ADR 0039), the control plane on the local profile, two laptops' fake GPUs, the web
# app's dev server and the public site. It never touches production settings or real money.
#
#   ./start.sh                  start everything, and open the website (Ctrl-C stops it all)
#   ./start.sh --no-browser     the same, without opening a browser (CI, or a machine with no screen)
#   ./start.sh --real-sign-in   sign in with Google too, from control-plane/.env and web/.env.local
#                               (docs/runbooks/identity-provider-setup.md)
#   ./start.sh --link-demo      also start a third fake laptop that links by approval, to try the flow: its approval
#                               page opens, you press Approve and start earning (docs/runbooks/hosts.md)
#   ./start.sh --lan            also serve a Windows laptop on the same Wi-Fi: the API, the website, the web app
#                               and storage on this Mac's LAN address, and a development release of the Windows app
#                               built for it and published to local storage (docs/windows-laptop-test.md)
#   ./start.sh --real-runner    run the jobs for real: both laptops' jobs run in real containers, through the job runner
#                               of a development distribution (scripts/dev-distro.sh) and the job image from the local
#                               registry (scripts/images/publish.sh --dev), under the real templates
#                               (templates/development-real.yaml: Fine-tune your model, Run your code). CPU-only, and arm64,
#                               on a Mac; a stack has the simulated jobs or the real ones, not both. With --lan, the Windows
#                               laptop runs them instead, with the CUDA image (publish.sh --dev --cuda), and the fake laptops
#                               take no offers
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
link_demo=false
lan=false
real_runner=false
action=start
for arg in "$@"; do
  case "$arg" in
    --no-browser) no_browser=true ;;
    --real-sign-in) real_sign_in=true ;;
    --fresh) fresh=true ;;
    --link-demo) link_demo=true ;;
    --lan) lan=true ;;
    --real-runner) real_runner=true ;;
    --stop) action=stop ;;
    --status) action=status ;;
    -h | --help) sed -n '2,27p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "start: unknown option $arg; ./start.sh --help lists them" >&2; exit 2 ;;
  esac
done

http_port=8080
grpc_port=9443
# The control plane's internal port for the image scanner (ADR 0042).
scanner_port=8095
sign_in_port=8099
storage_port=9100
mail_smtp_port=1025
mail_web_port=8025
grafana_port=3300
prometheus_port=9190
alertmanager_port=9193
web_port=3000
site_port=3002
razorpay_port=8091
razorpayx_port=8092
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

# Every service, the observability ones too (compose profile observability), so stopping and wiping reach them all.
compose() { COMPOSE_PROJECT_NAME="$compose_project" docker compose -f "$root/control-plane/compose.yaml" --profile observability --profile scanner "$@"; }

# Everything this script starts, by its pid file; the control plane's is a process group.
stop_all() {
  local pid_file pid
  for pid_file in "$state"/web.pid "$state"/site.pid "$state"/agent-*.pid "$state"/fake-razorpay.pid "$state"/fake-razorpayx.pid; do
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
  for name in control-plane fake-razorpay web site agent-1 agent-2 agent-3; do
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
java_major="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
[[ "$java_major" == 25 ]] ||
  fail "Java 25 is needed, found ${java_major:-none}. Install it (macOS: brew install openjdk@25; Linux: Temurin 25) and put it on PATH."
node_want="$(cat "$root/web/.node-version")"
node_have="$(node --version 2>/dev/null | tr -d v)"
[[ "$node_have" == "$node_want" ]] ||
  fail "Node $node_want is needed (web/.node-version), found ${node_have:-none}. Install it (macOS: brew install node@24) and put it on PATH."
command -v pnpm >/dev/null || fail "pnpm isn't available; it comes with Node $node_want through corepack. Reinstall Node, or run: corepack enable pnpm"
command -v rustup >/dev/null ||
  fail "rustup isn't installed. Install it from https://rustup.rs (macOS: brew install rustup), then run ./start.sh again."
rust_want="$(sed -n 's/^channel = "\(.*\)"/\1/p' "$root/agent/rust-toolchain.toml")"
if ! rustup toolchain list | grep -q "^$rust_want"; then
  log "installing Rust $rust_want (agent/rust-toolchain.toml), once"
  (cd "$root/agent" && rustup toolchain install) >>"$out/rust-install.log" 2>&1 ||
    fail "Rust $rust_want couldn't be installed; see logs/rust-install.log"
fi
for port in "$http_port" "$grpc_port" "$sign_in_port" "$storage_port" "$mail_smtp_port" "$mail_web_port" "$web_port" "$site_port" \
  "$grafana_port" "$prometheus_port" "$alertmanager_port" "$razorpay_port" "$razorpayx_port"; do
  if port_in_use "$port"; then
    fail "port $port is already in use. Stop what holds it (lsof -iTCP:$port -sTCP:LISTEN shows it), or ./start.sh --stop if it's an earlier start."
  fi
done
# The LAN address a Windows laptop reaches this Mac at: the default route's interface, or IDLEO_LAN_IP.
lan_ip=""
if $lan; then
  lan_ip="${IDLEO_LAN_IP:-}"
  if [[ -z "$lan_ip" ]]; then
    lan_interface="$(route -n get default 2>/dev/null | awk '/interface:/ {print $2}')"
    [[ -n "$lan_interface" ]] && lan_ip="$(ipconfig getifaddr "$lan_interface" 2>/dev/null || true)"
  fi
  [[ "$lan_ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
    fail "--lan couldn't find this Mac's LAN address. Join the Wi-Fi the laptop is on, or set IDLEO_LAN_IP=<address>."
  command -v cargo-xwin >/dev/null ||
    fail "--lan builds the Windows app here and needs cargo-xwin: cargo install --locked cargo-xwin (and brew install llvm lld)"
fi
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
# With --lan, the gRPC port's certificate also names the LAN address, which the laptop's agent connects to.
"$root/control-plane/scripts/dev-ca.sh" ${lan_ip:+--server-ip "$lan_ip"} >>"$out/dev-ca.log" 2>&1 || fail "the development CA couldn't be made; see logs/dev-ca.log"

# The development backup key (ADR 0036): the database encrypts its backups with its public half.
"$root/ops/backup/dev-key.sh" >>"$out/backup.log" 2>&1 || fail "the development backup key couldn't be made; see logs/backup.log"

# The development release key (ADR 0034), which the control plane trusts and the Windows app is built with.
if $lan; then
  "$root/scripts/release/dev-key.sh" >>"$out/release.log" 2>&1 || fail "the development release key couldn't be made; see logs/release.log"
fi

# The control plane, and meanwhile what can be built beside it.
stack_control_plane_settings() {
  # Anyone signing in locally is let in: no invite step on this machine.
  export IDENTITY_INVITE_ONLY=false
  # Metrics, traces and logs to development's Grafana, and alerts to Mailpit (ADR 0036, docs/runbooks/monitoring.md).
  export COMPOSE_PROFILES=observability SPRING_DOCKER_COMPOSE_PROFILES_ACTIVE=observability LOCAL_OBSERVABILITY=true
  export LOCAL_GRAFANA_PORT="$grafana_port" LOCAL_PROMETHEUS_PORT="$prometheus_port"
  export LOCAL_ALERTMANAGER_PORT="$alertmanager_port"
  # The database archives its WAL to this MinIO, encrypted, and the control plane watches the backups (ADR 0036).
  export LOCAL_BACKUP_PREFIX="s3://idleo-backups/local" LOCAL_BACKUP_PUBLIC_KEY="$root/private/backup-dev/backup.pub.asc"
  export BACKUPS_STORAGE_ENDPOINT="http://localhost:$storage_port" BACKUPS_STORAGE_BUCKET=idleo-backups
  export BACKUPS_STORAGE_PREFIX=local BACKUPS_STORAGE_REGION=us-east-1
  export BACKUPS_STORAGE_ACCESS_KEY_ID=local-storage-root BACKUPS_STORAGE_SECRET_ACCESS_KEY=local-only-not-a-secret
  # The development catalog, whose simulated services the fake GPUs' development runner can run; with --real-runner, the
  # real ones, with the digest of the image just pushed (private/local/development-real.yaml).
  export SESSIONS_TEMPLATES_CATALOG="file:$root/templates/development.yaml"
  if $real_runner; then
    SESSIONS_TEMPLATES_CATALOG="file:$state/development-real.yaml"
  fi
  # Origins that may call the API from a browser: the dev server, and the public site's pricing page.
  export WEB_ALLOWED_ORIGINS="http://localhost:$web_port,http://localhost:$site_port"
  if $lan; then
    # The laptop's browser opens the website and the web app at the LAN address; storage is reached there by the
    # laptop (the app's download, a session's files) and by this Mac alike, so presigned links work from both.
    WEB_ALLOWED_ORIGINS="$WEB_ALLOWED_ORIGINS,http://$lan_ip:$web_port,http://$lan_ip:$site_port"
    export SESSIONS_STORAGE_ENDPOINT="http://$lan_ip:$storage_port" LOCAL_STORAGE_BIND=0.0.0.0
    # With --real-runner the laptop pulls the job image from this Mac's registry.
    export LOCAL_REGISTRY_BIND=0.0.0.0
    export HOSTS_LINK_WEB_APP_URL="http://$lan_ip:$web_port"
  fi
}
# --real-runner: the registry and the job image come first, since the catalog names the image's digest. On a Mac the jobs run in
# a development distribution (arm64, CPU); with --lan, the Windows laptop runs them, in the CUDA image, and no fake laptop takes
# an offer.
if $real_runner; then
  command -v docker >/dev/null || fail "--real-runner needs Docker"
  # Every service, not just the registry: the control plane's own start then finds them running and starts nothing more.
  stack_compose_up >>"$out/dev-distro.log" 2>&1 || fail "compose's services didn't start; see logs/dev-distro.log"
  if $lan; then
    log "building and pushing the job image for the laptop (linux/amd64, CUDA; logs/job-image.log, minutes the first time)"
    "$root/scripts/images/publish.sh" --dev --cuda >"$out/job-image.log" 2>&1 || fail "the job image didn't build; see logs/job-image.log"
    stack_runner=""
  else
    docker image inspect "idleo-distro-dev:$(tr -d '[:space:]' <"$root/agent/distro/VERSION")" >/dev/null 2>&1 || {
      log "building the development distribution, once (logs/dev-distro-build.log)"
      "$root/scripts/build-distro.sh" --dev >"$out/dev-distro-build.log" 2>&1 || fail "the development distribution didn't build; see logs/dev-distro-build.log"
    }
    log "building and pushing the job image (logs/job-image.log)"
    "$root/scripts/images/publish.sh" --dev >"$out/job-image.log" 2>&1 || fail "the job image didn't build; see logs/job-image.log"
    "$root/scripts/dev-distro.sh" up >>"$out/dev-distro.log" 2>&1 || fail "the development distribution didn't start; see logs/dev-distro.log"
    stack_runner="--container-runner docker:${IDLEO_DEV_DISTRO:-idleo-dev-distro}"
  fi
fi

log "starting the fake Razorpay (logs/fake-razorpay.log)"
stack_start_fake_razorpay
log "starting the control plane (logs/control-plane.log)"
stack_start_control_plane
echo "$control_plane_pid" >"$state/control-plane.pid"

# The website and the app each know the other's address (web/src/build-settings.ts: IDLEO_SITE_URL, IDLEO_APP_URL).
# The Credit page pays through the fake Razorpay's Checkout (ADR 0039).
web_env=(IDLEO_DEPLOYMENT=local VITE_API_URL="$api" VITE_STORAGE_URL="http://localhost:$storage_port"
  IDLEO_SITE_URL="http://localhost:$site_port" IDLEO_APP_URL="http://localhost:$web_port"
  VITE_CHECKOUT_URL="http://localhost:$razorpay_port/v1/checkout.js")
if $lan; then
  # The laptop's browser calls the API (the website's Download button) at the LAN address; so does this Mac's.
  web_env+=(VITE_API_URL="http://$lan_ip:$http_port" VITE_STORAGE_URL="http://$lan_ip:$storage_port"
    VITE_CHECKOUT_URL="http://$lan_ip:$razorpay_port/v1/checkout.js")
fi
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

# The website: its own build, apart from the app's, built again only when its sources changed since the last build.
site_dist="$root/web/dist-website-local"
site_stamp="$state/site.stamp"
# (The API types and tokens are generated into web/src on every start, so they don't count.)
# (A change of settings, such as --lan's API address, counts too.)
site_settings="${web_env[*]}"
newest_source="$(find "$root/web/src" "$root/web/scripts" "$root/docs/site" "$root/templates" "$root/api/openapi.yaml" \
  -type f ! -name '*.gen.*' -newer "$site_stamp" 2>/dev/null | head -1 || true)"
if [[ ! -f "$site_stamp" || ! -d "$site_dist" || -n "$newest_source" || "$(cat "$site_stamp")" != "$site_settings" ]]; then
  log "building the website (logs/site-build.log)"
  (cd "$root/web" && env "${web_env[@]}" IDLEO_WEBSITE_DIST=dist-website-local IDLEO_SITE_DIST=dist-site-local pnpm build:site) \
    >"$out/site-build.log" 2>&1 || fail "the website's build failed; see logs/site-build.log"
  printf '%s' "$site_settings" >"$site_stamp"
fi
serve_host=127.0.0.1
dev_host=()
if $lan; then
  serve_host=0.0.0.0
  dev_host=(--host 0.0.0.0)
fi
(cd "$root/web" && IDLEO_DIST=dist-website-local PORT="$site_port" IDLEO_SERVE_HOST="$serve_host" exec node scripts/serve.ts) >>"$out/site.log" 2>&1 &
echo $! >"$state/site.pid"

# The web app's dev server, with live reload (pnpm dev makes the API types and tokens first), in its own process
# group, so stopping it reaches Vite under pnpm too.
set -m
(cd "$root/web" && exec env "${web_env[@]}" pnpm dev ${dev_host[@]+"${dev_host[@]}"} --port "$web_port" --strictPort) >>"$out/web.log" 2>&1 &
echo $! >"$state/web.pid"
set +m

wait "$agent_build_pid" || fail "the agent's build failed; see logs/agent-build.log"
stack_await_control_plane 600
log "starting the image scanner (docker logs ${compose_project}-scanner-1)"
stack_start_scanner >>"$out/compose.log" 2>&1 || fail "the image scanner didn't start; see logs/compose.log"
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
  # Enrolled once: the agent keeps its certificate and key in its data folder (host.crt, host.key), and
  # agent-N.host says which host they belong to, so a database wiped under it (another checkout's --fresh) means a
  # new enrolment, never a certificate the control plane no longer knows.
  if [[ -z "$host_id" || ! -f "$state/agent-$i/host.crt" || "$(cat "$state/agent-$i.host" 2>/dev/null)" != "$host_id" ]]; then
    log "adding the $model (enrolled once)"
    [[ -n "$host_id" ]] || host_id="$(stack_register_host "$owner_token" "$model" "local-host-$i")"
    rm -rf "$state/agent-$i" "$state/agent-$i.host"
    stack_agent_config "$i"
    stack_enrol_agent "$i" "$(stack_enrolment_code "$owner_token" "$host_id")"
    printf '%s\n' "$host_id" >"$state/agent-$i.host"
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
await "Grafana" "http://localhost:$grafana_port/api/health"
# A full backup when there's none from the last day, as the nightly one would have taken (docs/runbooks/backups.md).
if ! docker exec -u postgres "$compose_project-postgres-1" wal-g backup-list --detail --json 2>/dev/null | python3 -c '
import datetime, json, sys
newest = max((b["finish_time"] for b in json.load(sys.stdin)), default=None)
now = datetime.datetime.now(datetime.timezone.utc)
sys.exit(0 if newest and now - datetime.datetime.fromisoformat(newest.replace("Z", "+00:00")) < datetime.timedelta(hours=24) else 1)'; then
  log "taking a full backup of the database (logs/backup.log)"
  IDLEO_DATABASE_CONTAINER="$compose_project-postgres-1" "$root/ops/backup/backup-now.sh" >>"$out/backup.log" 2>&1 ||
    fail "the first full backup failed; see logs/backup.log"
fi
await "the web app" "http://localhost:$web_port/"
await "the website" "http://localhost:$site_port/"
for i in 1 2; do
  curl -sf "$api/v1/hosts/${host_ids[$i]}" -H "Authorization: Bearer $owner_token" | grep -q '"online":true' ||
    fail "the ${laptops[$((i - 1))]%%|*} went offline; see logs/agent-$i.log"
done

# The link demo (ADR 0032): a third fake laptop that asks to be linked, as a real host's app does. Its approval page is
# opened for you (a link lasts 10 minutes; start again for a new one). Approved, it enrols and connects by itself, and is
# pending until it is activated, as every new host is during the pilot.
link_url=""
if $link_demo; then
  log "starting the link demo laptop (logs/link-demo.log)"
  rm -rf "$state/agent-3" "$state/agent-3.host"
  stack_agent_config 3
  : >"$out/link-demo.log"
  set -m
  (
    "$agent" --config "$state/agent-3.toml" link --api "$api" --no-browser --name "Link demo laptop" \
      --fake-gpu --fake-gpu-model "RTX 3060 Laptop" --fake-gpu-vram-mb 6144 >>"$out/link-demo.log" 2>&1 &&
      IDLEO_LOG=info exec "$agent" --config "$state/agent-3.toml" run --fake-gpu --fake-gpu-model "RTX 3060 Laptop" \
        --fake-gpu-vram-mb 6144 --fake-free-disk-mb 200000 --dev-runner >>"$out/agent-3.log" 2>&1
  ) &
  echo $! >"$state/agent-3.pid"
  set +m
  deadline=$((SECONDS + 30))
  until link_url="$(sed -n 's/^Open this link on any device to approve: //p' "$out/link-demo.log" | head -1)" && [[ -n "$link_url" ]]; do
    ((SECONDS < deadline)) || fail "the link demo laptop didn't ask to be linked; see logs/link-demo.log"
    sleep 0.5
  done
fi

# --lan: a development release of the Windows app (ADR 0034) built for this LAN address, in local storage, once per
# address and code. Its setup program has the LAN addresses and the development CA baked in; publish.sh --dev reuses them
# (private/release-dev/setup.env) for the next release, which is how an update is tried (docs/windows-laptop-test.md).
lan_version=""
if $lan; then
  release_settings="$root/private/release-dev/setup.env"
  cat >"$release_settings" <<SETUP
IDLEO_SETUP_API_URL=http://$lan_ip:$http_port
IDLEO_SETUP_APP_URL=http://$lan_ip:$web_port
IDLEO_SETUP_SITE_URL=http://$lan_ip:$site_port
IDLEO_SETUP_CONTROL_PLANE=$lan_ip:$grpc_port
IDLEO_SETUP_CA_ROOT=$root/private/dev-ca/root.pem
SETUP
  # --real-runner: where the laptop's development distribution pulls the job image from (ADR 0035).
  if $real_runner; then printf 'IDLEO_SETUP_REGISTRY=%s:5000\n' "$lan_ip" >>"$release_settings"; fi
  # The release this stack serves, once the feed has been read (it is read at start and every 10 s).
  served_version() {
    local deadline=$((SECONDS + 15)) version=""
    until version="$(curl -sf "$api/v1/agent-releases/latest" | json_get .version 2>/dev/null)" && [[ -n "$version" ]]; do
      ((SECONDS < deadline)) || return 0
      sleep 1
    done
    printf '%s' "$version"
  }
  published="$(cat "$root/private/release-dev/published" 2>/dev/null || true)"
  last_version="${published%% *}"
  served="$(served_version)"
  # Published for this address from the code as it is now? (An older build would be offered to the laptop otherwise.)
  fingerprint="$("$root/scripts/release/source-fingerprint.sh")"
  if [[ -n "$served" && "$published" == "$served http://$lan_ip:$http_port $fingerprint" ]]; then
    lan_version="$served"
    log "the Windows app $served, built for $lan_ip from this code, is already published"
  else
    # Always a newer version than the last: the control plane never goes back to an older one.
    if [[ "$last_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
      lan_version="${last_version%.*}.$((${last_version##*.} + 1))"
    else
      lan_version=0.1.0
    fi
    distro_version="$(tr -d '[:space:]' <"$root/agent/distro/VERSION")"
    if [[ ! -s "$root/dist/distro/idleo-distro-$distro_version.tar.gz" ]]; then
      log "building the sealed Linux image, once (logs/release.log; a few minutes)"
      "$root/scripts/build-distro.sh" >>"$out/release.log" 2>&1 || fail "the sealed Linux image's build failed; see logs/release.log"
    fi
    log "building the Windows app $lan_version for $lan_ip and publishing it to local storage (logs/release.log; minutes)"
    "$root/scripts/release/publish.sh" --dev --version "$lan_version" --rollout 100 >>"$out/release.log" 2>&1 ||
      fail "the Windows app's release failed; see logs/release.log"
    deadline=$((SECONDS + 30))
    until [[ "$(served_version)" == "$lan_version" ]]; do
      ((SECONDS < deadline)) || fail "the control plane didn't pick up the Windows app $lan_version; see logs/control-plane.log"
    done
  fi
fi

cat <<READY

Everything is up, in $((SECONDS - started)) s.

  Website        http://localhost:$site_port/   (the public pages; its "Get started" and "Log in" lead to the app)
  Web app        http://localhost:$web_port
  Email          http://localhost:$mail_web_port   (Mailpit: every email the stack sends, none delivered)
  Monitoring     http://localhost:$grafana_port   (Grafana: Platform health, Marketplace and Fleet; alerts arrive in Mailpit)
  Backups        archived to MinIO, encrypted; ops/backup/backup-now.sh, restore.sh (docs/runbooks/backups.md)
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
READY
if $link_demo; then
  cat <<READY
  Link demo      a third laptop (RTX 3060 Laptop, 6 GB) is waiting to be linked. Approve it, signed in as
                 nikki@example.com, at $link_url
                 (the link lasts 10 minutes; it is pending until an admin or operator activates it)
READY
fi
if $lan; then
  cat <<READY
  Windows laptop the Windows app $lan_version is published, built for $lan_ip.

    On the Windows laptop (same Wi-Fi), open http://$lan_ip:$site_port → Download for Windows.
    Approve on this Mac: its approval page opens here by itself when the laptop asks. (The laptop's browser can't
    sign in over plain http, so approving there doesn't work; this Mac signs in at http://localhost:$web_port.)
    If macOS asks to allow incoming connections, click Allow.

READY
fi
cat <<READY
  Logs           logs/  (control-plane, web, site, agent-1, agent-2, ops, link-demo)
  Stop           Ctrl-C here, or ./start.sh --stop from another terminal

READY
log "ready"
if ! $no_browser; then
  # The website first, as a visitor arrives; its buttons lead into the app.
  if command -v open >/dev/null; then open "http://localhost:$site_port/"; elif command -v xdg-open >/dev/null; then xdg-open "http://localhost:$site_port/" >/dev/null 2>&1 || true; fi
  # The demo's approval page too, in a second tab, if there is one.
  if [[ -n "$link_url" ]]; then
    if command -v open >/dev/null; then open "$link_url"; elif command -v xdg-open >/dev/null; then xdg-open "$link_url" >/dev/null 2>&1 || true; fi
  fi
fi

# --lan: each new request to link a laptop, as its setup program makes it, opens its approval page on this Mac, where
# signing in works. Read from the database: a link has no owner until it's approved, so no API lists it.
opened_links=" ${link_url##*/link/} "
open_new_links() {
  local id
  for id in $(compose exec -T postgres psql -U controlplane -d controlplane -tA -c \
    "SELECT id FROM hosts.host_links WHERE approved_at IS NULL AND denied_at IS NULL AND expires_at > now() ORDER BY created_at" \
    2>/dev/null); do
    [[ "$opened_links" == *" $id "* || "$opened_links" == *" $id/ "* ]] && continue
    opened_links="$opened_links$id "
    log "a laptop asks to be linked: approve it at http://localhost:$web_port/link/$id/"
    if ! $no_browser && command -v open >/dev/null; then open "http://localhost:$web_port/link/$id/"; fi
  done
}

# Until Ctrl-C (or ./start.sh --stop): if the control plane stops, everything stops.
while kill -0 "$control_plane_pid" 2>/dev/null; do
  if $lan; then open_new_links; fi
  sleep 2
done
if [[ ! -f "$state/control-plane.pid" ]]; then
  log "stopped by ./start.sh --stop"
  exit 0
fi
log "the control plane stopped; stopping the rest (logs/control-plane.log says why)"
exit 1
