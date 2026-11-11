#!/usr/bin/env bash
# The console's screens on the seeded data (ADR 0057): 100k users, 2k hosts, 1M sessions, 300k payments, against the real
# stack in a real browser. Each screen paints in 1 s, and a sort or filter change in 200 ms (web/e2e/console-budgets.spec.ts).
# Seeding takes minutes, so this is weekly (.github/workflows/budgets.yml) and by hand. It prints every figure.
set -euo pipefail
cd "$(dirname "$0")/../.."
root="$(pwd)"
out="$root/private/console-budgets"
http_port="${E2E_HTTP_PORT:-8280}"
grpc_port="${E2E_GRPC_PORT:-9643}"
sign_in_port=8299
storage_port=9300
mail_smtp_port=1225
mail_web_port=8225
web_port=3200
# The console is the admin site's (ADR 0058), the one origin /admin/v1 answers.
admin_port=3202
compose_project=idleo-console-budgets
operator_name=console-budgets
log() { printf 'console-budgets: %s\n' "$*"; }
fail() { printf 'console-budgets: FAIL: %s\n' "$*" >&2; exit 1; }
# shellcheck source=scripts/lib/stack.sh
. "$root/scripts/lib/stack.sh"

control_plane_pid=""
web_pid=""
cleanup() {
  local status=$?
  set +e
  [[ -n "$web_pid" ]] && kill -TERM "$web_pid" 2>/dev/null
  if [[ -n "$control_plane_pid" ]] && kill -0 "$control_plane_pid" 2>/dev/null; then
    kill -TERM -- "-$control_plane_pid" 2>/dev/null
    for _ in $(seq 1 60); do kill -0 "$control_plane_pid" 2>/dev/null || break; sleep 0.5; done
    kill -KILL -- "-$control_plane_pid" 2>/dev/null
    wait "$control_plane_pid" 2>/dev/null
  fi
  COMPOSE_PROJECT_NAME="$compose_project" docker compose -f control-plane/compose.yaml down --volumes >/dev/null 2>&1
  exit "$status"
}
mkdir -p "$out"
trap cleanup EXIT
trap 'exit 130' INT TERM

docker info >/dev/null 2>&1 || fail "Docker isn't running"
for port in "$http_port" "$grpc_port" "$sign_in_port" "$storage_port" "$mail_smtp_port" "$mail_web_port" "$web_port" "$admin_port"; do
  ! port_in_use "$port" || fail "port $port is already in use"
done

stack_control_plane_settings() {
  export IDENTITY_INVITE_ONLY=false
}
control-plane/scripts/dev-ca.sh >>"$out/dev-ca.log" 2>&1
ops/backup/dev-key.sh >>"$out/backup.log" 2>&1
log "starting the stack"
stack_start_control_plane
stack_await_control_plane 300
stack_await_sign_in 60

log "seeding: this takes minutes"
started="$SECONDS"
ops seed-console || fail "seeding failed; see private/console-budgets/ops.log"
log "seeded in $((SECONDS - started)) s"

user="budgets-$(date +%s)"
token="$(stack_token "$user")"
curl -sf -H "Authorization: Bearer $token" "$api/v1/me" >/dev/null || fail "the admin's account wasn't made"
ops grant-admin "$user@example.com" --reason "'The console budgets'" --role owner || fail "grant-admin failed"

log "building the admin site"
(cd web && pnpm install --frozen-lockfile) >"$out/web-build.log" 2>&1
(cd web && IDLEO_WEB_SITES=admin IDLEO_SITE_URL="http://localhost:3201" IDLEO_APP_URL="http://localhost:$web_port" \
  IDLEO_ADMIN_URL="http://localhost:$admin_port" IDLEO_DEPLOYMENT=local \
  VITE_API_URL="$api" VITE_STORAGE_URL="http://localhost:$storage_port" VITE_AUTH=oidc-mock VITE_OIDC_ISSUER="$sign_in" \
  VITE_OIDC_CLIENT_ID=idleo-local VITE_CHECKOUT_URL="http://localhost:$web_port/none.js" pnpm build) >>"$out/web-build.log" 2>&1 ||
  fail "the web build failed; see private/console-budgets/web-build.log"
(cd web && IDLEO_DIST=dist-admin PORT="$admin_port" exec node scripts/serve.ts) >"$out/web-serve.log" 2>&1 &
web_pid=$!
until curl -sf "http://localhost:$admin_port/sign-in/" >/dev/null; do sleep 0.2; done

log "measuring every screen"
(cd web && ADMIN_URL="http://localhost:$admin_port" API_URL="$api" SIGN_IN_URL="$sign_in" SKIP_VISUAL=1 E2E_ADMIN_USER="$user" \
  CONSOLE_BUDGETS_REPORT="$out/report.json" pnpm exec playwright test --project=console-budgets --reporter=line)
log "PASS: every screen within its budgets; the figures are in private/console-budgets/report.json"
